package com.example.agent.plugin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.example.agent.plugin.PluginManagerTest.context;
import static com.example.agent.plugin.PluginManagerTest.tool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 官方 manager 只通过它的异常处理器把 {@link ToolExecutionException} 变成工具结果，而这个处理器在原因是受检异常时会重新抛出。
 * 这里让插件的各种失败经过同一个官方处理器，确认它们都变成了交给模型的结果。
 */
@ExtendWith(OutputCaptureExtension.class)
class PluginToolCallbackTest {

    private static final ToolExecutionExceptionProcessor OFFICIAL = DefaultToolExecutionExceptionProcessor.builder().build();

    @TempDir Path root;
    @TempDir Path build;
    private TestPluginHost host;
    private PluginLease lease;

    @BeforeEach
    void setUp() {
        host = new TestPluginHost(root);
        host.installAndEnable(TestPlugins.echoJar(build, "acme", "1.0.0", false, "[]"), "acme", "1.0.0");
        host.installAndEnable(rawJar(), "raw", "1.0.0");
        lease = host.manager.acquire(List.of("acme", "raw"));
    }

    @AfterEach
    void tearDown() {
        lease.close();
        host.close();
    }

    @Test
    void definitionAndMetadataAreThePluginsOwn() {
        ToolCallback write = tool(lease, "acme_write");
        assertThat(write.getToolDefinition().description()).isEqualTo("Pretends to write the text");
        assertThat(write.getToolMetadata().requiresConfirmation()).isTrue();
    }

    @Test
    void everyPluginExceptionBecomesAToolResultExceptErrors() {
        assertThat(resultOf(tool(lease, "raw_fail"), "{\"mode\":\"runtime\"}")).isEqualTo("raw runtime failure");
        assertThat(resultOf(tool(lease, "raw_fail"), "{\"mode\":\"checked\"}")).isEqualTo("IOException: raw checked failure");
        // 官方 MethodToolCallback 把 @Tool 方法抛出的受检异常原样包装，官方处理器本会把它重新抛出、中断整个请求。
        assertThat(resultOf(tool(lease, "acme_echo"), "{\"text\":\"fail\"}")).isEqualTo("IOException: echo failed");
        assertThatThrownBy(() -> tool(lease, "raw_fail").call("{\"mode\":\"error\"}", context(Map.of())))
                .isInstanceOf(AssertionError.class).hasMessage("raw error");
        assertThat(host.manager.view("raw").runtimes().getFirst().inFlight()).isZero();
    }

    @Test
    void runsWithThePluginsContextClassLoaderAndRestoresTheCallers() {
        ClassLoader caller = Thread.currentThread().getContextClassLoader();

        assertThat(tool(lease, "acme_echo").call("{\"text\":\"hi\"}", context(Map.of()))).contains("tccl=true");
        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(caller);
        assertThat(resultOf(tool(lease, "acme_echo"), "{\"text\":\"fail\"}")).isNotEmpty();
        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(caller);
    }

    @Test
    void auditsEachCallWithoutItsArgumentsOrResult(CapturedOutput output) {
        tool(lease, "acme_echo").call("{\"text\":\"secret-argument\"}", context(Map.of()));
        resultOf(tool(lease, "acme_echo"), "{\"text\":\"fail\"}");

        assertThat(output.getOut()).contains(
                "plugin.call plugin=acme version=1.0.0 tool=acme_echo userId=7 sessionId=s1 durationMs=",
                "outcome=success", "outcome=failure")
                .doesNotContain("secret-argument");
    }

    private static String resultOf(ToolCallback tool, String input) {
        try {
            return tool.call(input, context(Map.of()));
        } catch (ToolExecutionException e) {
            return OFFICIAL.process(e);
        }
    }

    /** 一个自己实现 ToolCallback 的插件：没有任何官方代码替它包装异常。 */
    private Path rawJar() {
        return TestPlugins.jar(build, TestPlugins.manifest("raw", "1.0.0", "test.raw.Plugin",
                "[{\"name\":\"raw_fail\"}]", "[]"), Map.of("test.raw.Plugin", """
                package test.raw;

                import com.example.agent.plugin.api.AgentPlugin;
                import com.example.agent.plugin.api.PluginContext;
                import org.springframework.ai.tool.ToolCallback;
                import org.springframework.ai.tool.definition.ToolDefinition;

                public class Plugin implements AgentPlugin {
                    @Override
                    public void start(PluginContext context) {
                        context.registerTools(new Raw());
                    }

                    static final class Raw implements ToolCallback {
                        @Override
                        public ToolDefinition getToolDefinition() {
                            return ToolDefinition.builder().name("raw_fail").description("Fails on demand")
                                    .inputSchema("{\\"type\\":\\"object\\",\\"properties\\":{\\"mode\\":{\\"type\\":\\"string\\"}}}").build();
                        }

                        @Override
                        public String call(String input) {
                            if (input.contains("runtime")) throw new IllegalStateException("raw runtime failure");
                            if (input.contains("checked")) throw Raw.<RuntimeException>sneaky(new java.io.IOException("raw checked failure"));
                            if (input.contains("error")) throw new AssertionError("raw error");
                            return "raw ok";
                        }

                        @SuppressWarnings("unchecked")
                        private static <T extends Throwable> T sneaky(Throwable failure) throws T {
                            throw (T) failure;
                        }
                    }
                }
                """));
    }
}
