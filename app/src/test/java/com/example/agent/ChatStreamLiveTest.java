package com.example.agent;

import com.example.agent.plugin.TestPlugins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用真实模型跑一次完整的流式对话：插件工具、需要审批的 Bash、SSE 事件，以及请求结束后的租约释放。
 *
 * <p>只有设置了环境变量 {@code DEEPSEEK_API_KEY} 才运行；模型和地址取 application.yaml 的默认值，可以用
 * {@code DEEPSEEK_MODEL}、{@code DEEPSEEK_BASE_URL} 覆盖。模型没有按要求调用工具时，测试以明确的断言失败：
 * 那说明需要重跑，而不是放宽断言。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class ChatStreamLiveTest {

    private static final Path ROOT = Path.of("target", "live-test", UUID.randomUUID().toString());
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void directories(DynamicPropertyRegistry registry) {
        registry.add("plugins.root", () -> ROOT.resolve("plugins").toString());
        registry.add("chat.workspace", () -> ROOT.resolve("workspace").toString());
    }

    @LocalServerPort int port;
    @TempDir Path build;

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void theRealModelCallsAPluginToolAndBashAfterApproval() throws Exception {
        String marker = "m" + UUID.randomUUID().toString().substring(0, 8);
        RestClient http = RestClient.create("http://localhost:" + port);
        MultiValueMap<String, Object> upload = new LinkedMultiValueMap<>();
        upload.add("file", new FileSystemResource(TestPlugins.echoJar(build, "acme", "1.0.0", false, "[]")));
        http.post().uri("/admin/plugins").contentType(MediaType.MULTIPART_FORM_DATA).body(upload).retrieve().toBodilessEntity();
        http.post().uri("/admin/plugins/acme/enable").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("version", "1.0.0")).retrieve().toBodilessEntity();

        String body = JSON.writeValueAsString(Map.of(
                "message", "Use your tools for both steps, then reply with one short sentence. "
                        + "Step 1: call the tool acme_echo with the text " + marker + ". "
                        + "Step 2: call the tool Bash to run the command: echo bash-" + marker,
                "plugins", List.of("acme"),
                "userId", "live-test"));
        List<JsonNode> events = new CopyOnWriteArrayList<>();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/chat/stream"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<java.util.stream.Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
            assertThat(response.statusCode()).isEqualTo(200);
            response.body().filter(line -> line.startsWith("data:")).map(line -> JSON.readTree(line.substring(5)))
                    .forEach(event -> {
                        events.add(event);
                        // 扮演审批人：批准这次对话里的每个审批请求（这里就是 Bash）。
                        if (type(event).equals("approval_request")) {
                            http.post().uri("/approvals/{id}", event.get("id").asString())
                                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("approve", true))
                                    .retrieve().toBodilessEntity();
                        }
                    });
        }

        String trace = events.stream().map(JsonNode::toString).collect(Collectors.joining("\n"));
        assertThat(events).as(trace).noneMatch(event -> type(event).equals("error"));
        assertThat(type(events.getLast())).as(trace).isEqualTo("finish");
        assertThat(events).as("the plugin tool ran on the leased version: " + trace).anyMatch(event ->
                type(event).equals("tool_result") && text(event, "tool").equals("acme_echo")
                        && text(event, "plugin").equals("acme@1.0.0") && text(event, "text").contains(marker + " v1.0.0"));
        assertThat(events).as("Bash asked for approval: " + trace).anyMatch(event ->
                type(event).equals("approval_request") && text(event, "tool").equals("Bash"));
        assertThat(events).as("Bash ran once approved: " + trace).anyMatch(event ->
                type(event).equals("tool_result") && text(event, "tool").equals("Bash")
                        && text(event, "text").contains("bash-" + marker));
        assertThat(events.stream().filter(event -> type(event).equals("token")).map(event -> text(event, "text"))
                .collect(Collectors.joining())).as("the model answered").isNotBlank();

        String plugin = http.get().uri("/admin/plugins/acme").retrieve().body(String.class);
        assertThat(JSON.readTree(plugin).get("runtimes").get(0).get("leases").asInt()).as("the lease was released").isZero();
    }

    private static String type(JsonNode event) {
        return text(event, "type");
    }

    private static String text(JsonNode event, String field) {
        JsonNode value = event.get(field);
        return value == null || value.isNull() ? "" : value.asString();
    }
}
