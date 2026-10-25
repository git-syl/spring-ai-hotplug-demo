package com.example.agent.plugin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.util.unit.DataSize;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static com.example.agent.plugin.TestPluginHost.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/** 运行实例的生命周期：启动与回滚、按请求租用、先启动后切换、退役、审批规则和类加载器回收。 */
class PluginManagerTest {

    private static final String GREETING = "[{\"key\":\"greeting\",\"default\":\"hello\"},{\"key\":\"token\",\"secret\":true}]";

    @TempDir Path root;
    @TempDir Path build;
    private TestPluginHost host;

    @BeforeEach
    void setUp() {
        host = new TestPluginHost(root);
    }

    @AfterEach
    void tearDown() {
        host.close();
    }

    @Test
    void enablesAVersionAndLeasesItsToolsToRequests() throws Exception {
        PluginView view = host.installAndEnable(TestPlugins.echoJar(build, "acme", "1.0.0", false, GREETING), "acme", "1.0.0");

        assertThat(view.state()).isEqualTo(PluginView.State.RUNNING);
        assertThat(view.runtimes()).extracting(PluginView.RuntimeView::version, PluginView.RuntimeView::state)
                .containsExactly(tuple("1.0.0", "RUNNING"));
        try (PluginLease lease = host.manager.acquire(List.of("acme"))) {
            assertThat(Arrays.stream(lease.callbacks()).map(tool -> tool.getToolDefinition().name()))
                    .as("in manifest order").containsExactly("acme_echo", "acme_write");
            assertThat(lease.pluginOf("acme_echo")).isEqualTo("acme@1.0.0");
            assertThat(host.manager.view("acme").runtimes().getFirst().leases()).isEqualTo(1);
            assertThat(tool(lease, "acme_echo").call("{\"text\":\"hi\"}", context(Map.of())))
                    .isEqualTo("\"hello hi v1.0.0 user=7 session=s1 tccl=true\"");
        }
        assertThat(host.manager.view("acme").runtimes().getFirst().leases()).isZero();
    }

    @Test
    void aFailedStartClosesWhatItRegisteredInReverseAndLeavesTheServiceAvailable() throws Exception {
        host.install(failingJar());

        assertThatThrownBy(() -> host.manager.enable("failing", "1.0.0"))
                .isInstanceOfSatisfying(PluginException.class, e -> assertThat(e.code()).isEqualTo(PluginException.Code.START_FAILED))
                .hasMessageContaining("IllegalStateException: start exploded");
        Path closed = host.store.dataDirectory("failing").resolve("closed.txt");
        assertThat(Files.readAllLines(closed)).as("closed in reverse order of registration").containsExactly("second", "first");
        PluginView view = host.manager.view("failing");
        assertThat(view.state()).isEqualTo(PluginView.State.DISABLED);
        assertThat(view.lastError()).contains("start exploded");
        assertThat(view.runtimes()).isEmpty();

        // 一个记录为已启用、却起不来的版本（例如服务重启之后）是 FAILED，点名它的请求被拒绝。
        host.store.setEnabledVersion("failing", "1.0.0");
        try (var restarted = new TestPluginHost(root)) {
            restarted.manager.startEnabledPlugins();
            assertThat(restarted.manager.view("failing").state()).isEqualTo(PluginView.State.FAILED);
            assertRefused(() -> restarted.manager.acquire(List.of("failing")), PluginException.Code.INVALID_REQUEST,
                    "is not running (FAILED)");
        }
    }

    @Test
    void aStaticInitializerThatThrowsIsAStartFailureNotACrash() {
        // static 块抛出的异常到达宿主时是 ExceptionInInitializerError——一个 Error，catch (Exception e) 接不住它。
        Path jar = TestPlugins.jar(build, TestPlugins.manifest("broken", "1.0.0", "test.broken.Plugin",
                "[{\"name\":\"broken_noop\"}]", "[]"), Map.of("test.broken.Plugin", """
                package test.broken;

                import com.example.agent.plugin.api.AgentPlugin;
                import com.example.agent.plugin.api.PluginContext;

                public class Plugin implements AgentPlugin {
                    static {
                        if (true) throw new IllegalStateException("static block exploded");
                    }

                    @Override
                    public void start(PluginContext context) {
                    }
                }
                """));
        host.install(jar);

        assertRefused(() -> host.manager.enable("broken", "1.0.0"), PluginException.Code.START_FAILED,
                "IllegalStateException: static block exploded");
        assertThat(host.manager.view("broken").runtimes()).isEmpty();
    }

    @Test
    void registeredToolsMustEqualTheManifest() {
        Path jar = TestPlugins.jar(build, TestPlugins.manifest("acme", "1.0.0", "test.acme.Plugin",
                "[{\"name\":\"acme_echo\"},{\"name\":\"acme_write\"},{\"name\":\"acme_missing\"}]", "[]"),
                Map.of("test.acme.Plugin", TestPlugins.echoPlugin("acme")));
        host.install(jar);

        assertThatThrownBy(() -> host.manager.enable("acme", "1.0.0"))
                .hasMessageContaining("registered tools [acme_echo, acme_write] differ from the manifest's");
    }

    @Test
    void resolvesEnvironmentReferencesAndDefaultsWhenItStarts() throws Exception {
        host.install(TestPlugins.echoJar(build, "acme", "1.0.0", false, "[{\"key\":\"greeting\",\"required\":true}]"));

        assertThatThrownBy(() -> host.manager.enable("acme", "1.0.0"))
                .hasMessageContaining("required config key 'greeting' has no value");
        host.manager.configure("acme", Map.of("greeting", "${TEST_GREETING}!"), List.of());
        assertThatThrownBy(() -> host.manager.enable("acme", "1.0.0"))
                .hasMessageContaining("references the unset environment variable TEST_GREETING");

        host.environment.put("TEST_GREETING", "hi");
        host.manager.enable("acme", "1.0.0");
        assertThat(echo("acme", Map.of())).startsWith("\"hi! hi v1.0.0");
        assertThat(host.manager.view("acme").config()).singleElement()
                .satisfies(key -> assertThat(key.value()).as("responses show the raw value").isEqualTo("${TEST_GREETING}!"));
    }

    @Test
    void configurationIsValidatedAndSecretsAreMasked() {
        host.install(TestPlugins.echoJar(build, "acme", "1.0.0", false, GREETING));

        assertThatThrownBy(() -> host.manager.configure("acme", Map.of("greting", "x"), List.of()))
                .hasMessageContaining("declares no config key greting");
        PluginView view = host.manager.configure("acme", Map.of("token", "${ACME_TOKEN}", "greeting", "hi"), List.of("acme_echo"));
        assertThat(view.config()).extracting(PluginView.ConfigView::key, PluginView.ConfigView::value)
                .containsExactly(tuple("greeting", "hi"), tuple("token", PluginManager.MASK));

        // 原样发回掩码表示保留原来的密钥；其它值整体替换。
        host.manager.configure("acme", Map.of("token", PluginManager.MASK), List.of());
        assertThat(host.store.entry("acme").orElseThrow().config()).containsExactly(Map.entry("token", "${ACME_TOKEN}"));
    }

    @Test
    void reloadRestartsTheEnabledVersionWithTheCurrentConfiguration() {
        host.installAndEnable(TestPlugins.echoJar(build, "acme", "1.0.0", false, GREETING), "acme", "1.0.0");
        host.manager.configure("acme", Map.of("greeting", "hey"), List.of());
        assertThat(echo("acme", Map.of())).as("configuration applies at the next start").startsWith("\"hello");

        host.manager.reload("acme");
        assertThat(echo("acme", Map.of())).startsWith("\"hey");
    }

    @Test
    void switchingVersionsLetsOpenLeasesFinishOnTheOldRuntime() {
        host.installAndEnable(TestPlugins.echoJar(build, "acme", "1.0.0", false, "[]"), "acme", "1.0.0");
        host.install(TestPlugins.echoJar(build, "acme", "2.0.0", false, "[]"));

        try (PluginLease old = host.manager.acquire(List.of("acme"))) {
            host.manager.enable("acme", "2.0.0");

            assertThat(host.manager.view("acme").runtimes())
                    .extracting(PluginView.RuntimeView::version, PluginView.RuntimeView::state, PluginView.RuntimeView::leases)
                    .containsExactly(tuple("2.0.0", "RUNNING", 0), tuple("1.0.0", "RETIRING", 1));
            assertThat(tool(old, "acme_echo").call("{\"text\":\"x\"}", context(Map.of()))).contains("v1.0.0");
            assertThat(echo("acme", Map.of())).contains("v2.0.0");
        }
        host.awaitRetired("acme");
        assertThat(host.manager.view("acme").runtimes()).extracting(PluginView.RuntimeView::version).containsExactly("2.0.0");
    }

    @Test
    void aFailedSwitchLeavesTheOldVersionServing() {
        host.installAndEnable(TestPlugins.echoJar(build, "acme", "1.0.0", false, "[]"), "acme", "1.0.0");
        host.install(TestPlugins.echoJar(build, "acme", "2.0.0", false, "[{\"key\":\"greeting\",\"required\":true}]"));

        assertRefused(() -> host.manager.enable("acme", "2.0.0"), PluginException.Code.START_FAILED, "has no value");
        PluginView view = host.manager.view("acme");
        assertThat(view.state()).isEqualTo(PluginView.State.RUNNING);
        assertThat(view.enabledVersion()).isEqualTo("1.0.0");
        assertThat(view.lastError()).contains("required config key 'greeting' has no value");
        assertThat(echo("acme", Map.of())).contains("v1.0.0");
    }

    @Test
    void aCancelledRequestsRunningCallKeepsItsRuntimeUntilTheCallEnds() throws Exception {
        host.installAndEnable(TestPlugins.echoJar(build, "acme", "1.0.0", false, "[]"), "acme", "1.0.0");
        CountDownLatch release = new CountDownLatch(1);
        PluginLease lease = host.manager.acquire(List.of("acme"));
        ToolCallback echo = tool(lease, "acme_echo");
        CompletableFuture<String> call = CompletableFuture.supplyAsync(
                () -> echo.call("{\"text\":\"slow\"}", context(Map.of("latch", release))));
        await(() -> host.manager.view("acme").runtimes().getFirst().inFlight() == 1, "the call to start");

        lease.close(); // 请求被取消时，租约立刻释放……
        host.manager.disable("acme");

        assertThat(host.manager.view("acme").runtimes()).singleElement().satisfies(runtime -> {
            assertThat(runtime.state()).isEqualTo("RETIRING");
            assertThat(runtime.leases()).isZero();
            assertThat(runtime.inFlight()).as("……但正在执行的调用仍然挂住这个实例").isEqualTo(1);
        });
        release.countDown();
        assertThat(call.get(5, TimeUnit.SECONDS)).contains("slow v1.0.0");
        host.awaitRetired("acme");
        assertThat(host.manager.view("acme").state()).isEqualTo(PluginView.State.DISABLED);
    }

    @Test
    void aRetiringRuntimeIsStoppedOnceTheRetireTimeoutExpires() {
        try (var impatient = new TestPluginHost(root, DataSize.ofMegabytes(50), Duration.ofMillis(200))) {
            impatient.installAndEnable(TestPlugins.echoJar(build, "acme", "1.0.0", false, "[]"), "acme", "1.0.0");
            try (PluginLease forgotten = impatient.manager.acquire(List.of("acme"))) {
                impatient.manager.disable("acme");
                impatient.awaitRetired("acme");

                assertThatThrownBy(() -> tool(forgotten, "acme_echo").call("{\"text\":\"late\"}", context(Map.of())))
                        .isInstanceOf(ToolExecutionException.class).hasMessageContaining("acme@1.0.0 has stopped");
            }
        }
    }

    @Test
    void confirmationFollowsTheManifestsOfLiveRuntimesAndTheAdministratorsList() {
        host.installAndEnable(TestPlugins.echoJar(build, "acme", "1.0.0", true, "[]"), "acme", "1.0.0");
        host.install(TestPlugins.echoJar(build, "acme", "2.0.0", false, "[]"));
        assertThat(host.manager.requiresConfirmation("acme_echo")).isTrue();
        assertThat(host.manager.requiresConfirmation("acme_write")).as("@Tool metadata is the official policy's job").isFalse();

        try (PluginLease old = host.manager.acquire(List.of("acme"))) {
            host.manager.enable("acme", "2.0.0");
            assertThat(host.manager.requiresConfirmation("acme_echo"))
                    .as("calls may still run on the retiring 1.0.0, which asks for confirmation").isTrue();
        }
        host.awaitRetired("acme");
        assertThat(host.manager.requiresConfirmation("acme_echo")).isFalse();

        host.manager.configure("acme", Map.of(), List.of("acme_echo"));
        assertThat(host.manager.requiresConfirmation("acme_echo")).as("confirmTools applies at once").isTrue();
        assertThat(host.manager.requiresConfirmation("other_echo")).isFalse();
    }

    @Test
    void requestsNamingUnknownStoppedOrDuplicatePluginsAreRefused() {
        host.installAndEnable(TestPlugins.echoJar(build, "acme", "1.0.0", false, "[]"), "acme", "1.0.0");
        host.install(TestPlugins.echoJar(build, "beta", "1.0.0", false, "[]"));

        assertThat(host.manager.acquire(null).callbacks()).isEmpty();
        assertRefused(() -> host.manager.acquire(List.of("nope")), PluginException.Code.INVALID_REQUEST, "Unknown plugin: nope");
        assertRefused(() -> host.manager.acquire(List.of("beta")), PluginException.Code.INVALID_REQUEST,
                "beta is not running (DISABLED)");
        assertRefused(() -> host.manager.acquire(List.of("acme", "acme")), PluginException.Code.INVALID_REQUEST, "duplicate");
        assertThat(host.manager.view("acme").runtimes().getFirst().leases()).as("a refused request holds nothing").isZero();
    }

    @Test
    void uninstallingNeedsADisabledPluginWithNoRetiringRuntime() throws Exception {
        host.installAndEnable(TestPlugins.echoJar(build, "acme", "1.0.0", false, "[]"), "acme", "1.0.0");
        assertRefused(() -> host.manager.uninstall("acme", false), PluginException.Code.PLUGIN_RUNNING, "disable it first");

        try (PluginLease open = host.manager.acquire(List.of("acme"))) {
            host.manager.disable("acme");
            assertRefused(() -> host.manager.uninstall("acme", false), PluginException.Code.RUNTIME_RETIRING, "retiring runtime");
        }
        host.awaitRetired("acme");
        Files.writeString(host.store.dataDirectory("acme").resolve("state.txt"), "kept");
        host.manager.uninstall("acme", false);
        assertRefused(() -> host.manager.view("acme"), PluginException.Code.PLUGIN_NOT_FOUND, "Plugin not found");
        assertThat(root.resolve("acme/versions")).doesNotExist();
        assertThat(host.store.dataDirectory("acme").resolve("state.txt")).as("data survives unless purged").hasContent("kept");
    }

    @Test
    void leasesStayConsistentWhileVersionsSwitchUnderLoad() throws Exception {
        host.installAndEnable(TestPlugins.echoJar(build, "acme", "1.0.0", false, "[]"), "acme", "1.0.0");
        host.install(TestPlugins.echoJar(build, "acme", "2.0.0", false, "[]"));
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        AtomicBoolean running = new AtomicBoolean(true);
        try (var pool = Executors.newFixedThreadPool(6)) {
            for (int worker = 0; worker < 6; worker++) {
                pool.submit(() -> {
                    while (running.get()) {
                        try (PluginLease lease = host.manager.acquire(List.of("acme"))) {
                            assertThat(tool(lease, "acme_echo").call("{\"text\":\"load\"}", context(Map.of()))).contains("load v");
                        } catch (Throwable e) {
                            failures.add(e);
                        }
                    }
                });
            }
            for (int i = 0; i < 20; i++) host.manager.enable("acme", i % 2 == 0 ? "2.0.0" : "1.0.0");
            running.set(false);
        }
        assertThat(failures).isEmpty();
        host.awaitRetired("acme");
        assertThat(host.manager.view("acme").runtimes()).singleElement().satisfies(runtime -> {
            assertThat(runtime.version()).isEqualTo("1.0.0");
            assertThat(runtime.leases()).isZero();
            assertThat(runtime.inFlight()).isZero();
        });
    }

    @Test
    void aStoppedRuntimesClassLoaderCanBeCollected() throws Exception {
        host.installAndEnable(TestPlugins.echoJar(build, "acme", "1.0.0", false, "[]"), "acme", "1.0.0");
        List<WeakReference<ClassLoader>> loader = new ArrayList<>();
        callOnce(loader);
        host.manager.disable("acme");
        host.awaitRetired("acme");

        assertThat(loader).hasSize(1);
        for (int i = 0; i < 50 && loader.getFirst().get() != null; i++) {
            System.gc();
            Thread.sleep(100);
        }
        assertThat(loader.getFirst().get()).as("the plugin's class loader after GC").isNull();
    }

    /** 调用一次工具，由插件把它的类加载器交出来；只以弱引用保存，不挂住它。 */
    private void callOnce(List<WeakReference<ClassLoader>> loader) {
        Consumer<ClassLoader> sink = classLoader -> loader.add(new WeakReference<>(classLoader));
        try (PluginLease lease = host.manager.acquire(List.of("acme"))) {
            tool(lease, "acme_echo").call("{\"text\":\"hi\"}", context(Map.of("loader", sink)));
        }
    }

    private String echo(String id, Map<String, Object> extra) {
        try (PluginLease lease = host.manager.acquire(List.of(id))) {
            return tool(lease, id + "_echo").call("{\"text\":\"hi\"}", context(extra));
        }
    }

    /** 入口登记两个关闭器后抛出异常。 */
    private Path failingJar() {
        return TestPlugins.jar(build, TestPlugins.manifest("failing", "1.0.0", "test.failing.Plugin",
                "[{\"name\":\"failing_noop\"}]", "[]"), Map.of("test.failing.Plugin", """
                package test.failing;

                import com.example.agent.plugin.api.AgentPlugin;
                import com.example.agent.plugin.api.PluginContext;
                import java.nio.file.Files;
                import java.nio.file.StandardOpenOption;

                public class Plugin implements AgentPlugin {
                    @Override
                    public void start(PluginContext context) {
                        var closed = context.dataDirectory().resolve("closed.txt");
                        context.onStop(() -> Files.writeString(closed, "first\\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND));
                        context.onStop(() -> Files.writeString(closed, "second\\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND));
                        throw new IllegalStateException("start exploded");
                    }
                }
                """));
    }

    static ToolCallback tool(PluginLease lease, String name) {
        return Arrays.stream(lease.callbacks()).filter(tool -> tool.getToolDefinition().name().equals(name))
                .findFirst().orElseThrow();
    }

    static ToolContext context(Map<String, Object> extra) {
        Map<String, Object> context = new HashMap<>(Map.of("userId", "7", "sessionId", "s1"));
        context.putAll(extra);
        return new ToolContext(context);
    }

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                      PluginException.Code code, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PluginException.class,
                e -> assertThat(e.code()).isEqualTo(code)).hasMessageContaining(message);
    }
}
