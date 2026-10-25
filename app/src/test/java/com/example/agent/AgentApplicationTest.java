package com.example.agent;

import com.example.agent.plugin.PluginLease;
import com.example.agent.plugin.PluginManager;
import com.example.agent.plugin.TestPlugins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.tools.ShellTools;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallConfirmationRequiredException;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 启动完整的应用（不调用模型），检查装配：默认的 Bash 工具、官方确认策略与插件审批规则的组合、管理接口和请求校验。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentApplicationTest {

    private static final Path ROOT = Path.of("target", "app-test", UUID.randomUUID().toString());

    @DynamicPropertySource
    static void directories(DynamicPropertyRegistry registry) {
        registry.add("plugins.root", () -> ROOT.resolve("plugins").toString());
        registry.add("chat.workspace", () -> ROOT.resolve("workspace").toString());
    }

    @LocalServerPort int port;
    @Autowired ToolCallingManager toolCallingManager;
    @Autowired ShellTools shellTools;
    @Autowired PluginManager plugins;
    @TempDir Path build;

    @Test
    void theAutoConfiguredToolLoopPausesBashAndThePluginToolsThatNeedApproval() {
        RestClient http = RestClient.create("http://localhost:" + port);
        MultiValueMap<String, Object> upload = new LinkedMultiValueMap<>();
        upload.add("file", new FileSystemResource(TestPlugins.echoJar(build, "acme", "1.0.0", false, "[]")));
        assertThat(http.post().uri("/admin/plugins").contentType(MediaType.MULTIPART_FORM_DATA).body(upload)
                .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(201);
        assertThat(http.post().uri("/admin/plugins/acme/enable").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("version", "1.0.0")).retrieve().body(String.class)).contains("\"state\":\"RUNNING\"");

        List<ToolCallback> tools = new ArrayList<>(Arrays.asList(ToolCallbacks.from(shellTools)));
        // 反射不保证方法顺序，所以这里不比较顺序。
        assertThat(tools).extracting(tool -> tool.getToolDefinition().name())
                .containsExactlyInAnyOrder("Bash", "BashOutput", "KillShell");
        try (PluginLease lease = plugins.acquire(List.of("acme"))) {
            tools.addAll(Arrays.asList(lease.callbacks()));
            Prompt prompt = new Prompt(List.of(new UserMessage("test")), ToolCallingChatOptions.builder().toolCallbacks(tools)
                    .toolContext(Map.of("userId", "7", "sessionId", "s1")).build());

            // spring.ai.tools.confirmation.tools: [Bash]
            assertThatThrownBy(() -> toolCallingManager.executeToolCalls(prompt, reply("Bash", "{\"command\":\"echo hi\"}")))
                    .isInstanceOf(ToolCallConfirmationRequiredException.class);
            // 工具自身的 @Tool(requiresConfirmation = true)
            assertThatThrownBy(() -> toolCallingManager.executeToolCalls(prompt, reply("acme_write", "{\"text\":\"x\"}")))
                    .isInstanceOf(ToolCallConfirmationRequiredException.class);
            // 不需要审批的插件工具照常执行
            assertThat(resultOf(toolCallingManager.executeToolCalls(prompt, reply("acme_echo", "{\"text\":\"x\"}"))))
                    .contains("x v1.0.0");

            // 管理员把 acme_echo 加入 confirmTools：插件的审批规则（一个 ToolCallConfirmationPolicy bean）立即生效
            http.put().uri("/admin/plugins/acme/config").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("values", Map.of(), "confirmTools", List.of("acme_echo"))).retrieve().toBodilessEntity();
            assertThatThrownBy(() -> toolCallingManager.executeToolCalls(prompt, reply("acme_echo", "{\"text\":\"x\"}")))
                    .isInstanceOf(ToolCallConfirmationRequiredException.class);
        }
    }

    @Test
    void chatRequestsAreCheckedBeforeTheStreamStarts() {
        RestClient http = RestClient.create("http://localhost:" + port);

        ResponseEntity<String> unknown = http.post().uri("/chat/stream").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", "hi", "plugins", List.of("nope"))).retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> { }).toEntity(String.class);
        assertThat(unknown.getStatusCode().value()).isEqualTo(400);
        assertThat(unknown.getBody()).contains("\"code\":\"invalid_request\"", "Unknown plugin: nope");

        // SSE 客户端会带 Accept: text/event-stream，错误仍然是 400 和 JSON 错误体，而不是 500。
        ResponseEntity<String> fromAnSseClient = http.post().uri("/chat/stream").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).body(Map.of("message", "hi", "plugins", List.of("nope"))).retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> { }).toEntity(String.class);
        assertThat(fromAnSseClient.getStatusCode().value()).isEqualTo(400);
        assertThat(fromAnSseClient.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(fromAnSseClient.getBody()).contains("Unknown plugin: nope");

        ResponseEntity<String> blank = http.post().uri("/chat/stream").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", " ")).retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> { }).toEntity(String.class);
        assertThat(blank.getStatusCode().value()).isEqualTo(400);
        assertThat(blank.getBody()).contains("message is required");
    }

    @Test
    void servesTheChatPageAtTheRoot() {
        ResponseEntity<String> page = RestClient.create("http://localhost:" + port).get().uri("/")
                .retrieve().toEntity(String.class);

        assertThat(page.getStatusCode().value()).isEqualTo(200);
        assertThat(page.getHeaders().getContentType()).isNotNull()
                .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.TEXT_HTML)).isTrue());
        assertThat(page.getBody()).contains("/chat/stream", "/admin/plugins", "/approvals/");
    }

    /** 一次只要求调用一个工具的模型回复。 */
    private static ChatResponse reply(String tool, String arguments) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", tool, arguments))).build())));
    }

    private static String resultOf(ToolExecutionResult result) {
        return ((ToolResponseMessage) result.conversationHistory().getLast()).getResponses().getFirst().responseData();
    }
}
