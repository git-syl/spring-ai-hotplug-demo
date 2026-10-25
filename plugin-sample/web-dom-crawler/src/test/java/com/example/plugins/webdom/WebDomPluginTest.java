package com.example.plugins.webdom;

import com.example.agent.plugin.api.PluginContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 像宿主那样启动插件（使用清单里的默认配置），检查它注册了什么、工具能不能用。 */
class WebDomPluginTest {

    @Test
    void theBuiltManifestMatchesWhatThePluginRegisters() throws Exception {
        JsonNode manifest = manifest();
        assertThat(manifest.get("id").asString()).isEqualTo("webdom");
        assertThat(manifest.get("version").asString()).as("filtered from the POM").matches("\\d+\\.\\d+\\.\\d+.*");
        assertThat(manifest.get("entrypoint").asString()).isEqualTo(WebDomPlugin.class.getName());

        Context context = new Context(defaults(manifest));
        new WebDomPlugin().start(context);

        List<String> declared = manifest.get("tools").values().stream().map(tool -> tool.get("name").asString()).toList();
        assertThat(context.toolNames()).containsExactlyElementsOf(declared);
        assertThat(context.stopActions).hasSize(1);
        context.stopActions.getFirst().close();
    }

    @Test
    void theToolOutlinesAPageItFetched() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] page = "<html><head><title>Shop</title></head><body><main><h1>Prices</h1><p>Tea</p></main></body></html>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, page.length);
            try (var out = exchange.getResponseBody()) {
                out.write(page);
            }
        });
        server.start();
        Map<String, String> config = defaults(manifest());
        config.put("allowPrivateNetwork", "true");
        Context context = new Context(config);
        try {
            new WebDomPlugin().start(context);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";

            String result = context.tool("webdom_fetch_dom").call("{\"url\":\"" + url + "\",\"selector\":\"main\",\"maxDepth\":1}");

            assertThat(result).contains("URL: " + url, "Status: 200", "Title: Shop", "Selector 'main' matched 1 element.",
                    "main", "  h1 \\\"Prices\\\"", "  p \\\"Tea\\\"");
        } finally {
            for (AutoCloseable action : context.stopActions) action.close();
            server.stop(0);
        }
    }

    @Test
    void refusesConfigurationThatIsNotAPositiveNumber() throws Exception {
        Map<String, String> config = defaults(manifest());
        config.put("maxBytes", "lots");

        assertThatThrownBy(() -> new WebDomPlugin().start(new Context(config)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("config maxBytes must be a positive integer, not lots");
    }

    private static JsonNode manifest() throws Exception {
        try (InputStream in = WebDomPluginTest.class.getResourceAsStream("/META-INF/agent-plugin.json")) {
            return JsonMapper.builder().build().readTree(in);
        }
    }

    /** 管理员什么都没设置时宿主传入的配置：清单声明的全部默认值。 */
    private static Map<String, String> defaults(JsonNode manifest) {
        Map<String, String> config = new HashMap<>();
        for (JsonNode key : manifest.get("config").values()) config.put(key.get("key").asString(), key.get("default").asString());
        return config;
    }

    private static final class Context implements PluginContext {
        private final Map<String, String> config;
        private final List<Object> tools = new ArrayList<>();
        private final List<AutoCloseable> stopActions = new ArrayList<>();

        Context(Map<String, String> config) {
            this.config = config;
        }

        @Override public String pluginId() { return "webdom"; }
        @Override public String version() { return "test"; }
        @Override public Map<String, String> config() { return config; }
        @Override public Path dataDirectory() { return Path.of("target", "webdom-data"); }
        @Override public void registerTools(Object... registered) { tools.addAll(Arrays.asList(registered)); }
        @Override public void onStop(AutoCloseable resource) { stopActions.add(resource); }

        List<String> toolNames() {
            return Arrays.stream(ToolCallbacks.from(tools.toArray())).map(tool -> tool.getToolDefinition().name()).toList();
        }

        ToolCallback tool(String name) {
            return Arrays.stream(ToolCallbacks.from(tools.toArray()))
                    .filter(tool -> tool.getToolDefinition().name().equals(name)).findFirst().orElseThrow();
        }
    }
}
