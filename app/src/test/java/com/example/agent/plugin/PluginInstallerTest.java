package com.example.agent.plugin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 安装只读不执行：校验上传、发布不可变版本，失败不留文件，而且绝不运行插件代码。 */
class PluginInstallerTest {

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
    void installsAnImmutableVersionWithoutEnablingIt() throws Exception {
        Path jar = TestPlugins.echoJar(build, "acme", "1.0.0", false, "[]");

        var result = install(jar, sha256(jar).toUpperCase());

        assertThat(result.created()).isTrue();
        assertThat(result.plugin().state()).isEqualTo(PluginView.State.DISABLED);
        assertThat(result.plugin().versions()).singleElement()
                .satisfies(version -> assertThat(version.sha256()).isEqualTo(sha256(jar)));
        assertThat(result.plugin().tools()).extracting(PluginView.ToolView::name).containsExactly("acme_echo", "acme_write");
        assertThat(root.resolve("acme/versions/1.0.0/plugin.jar")).hasSameBinaryContentAs(jar);
        assertThat(root.resolve("registry.json")).content().contains("\"acme\"", sha256(jar));
        assertThat(root.resolve(".staging")).isEmptyDirectory();

        assertThat(install(jar, null).created()).as("an identical upload is a no-op").isFalse();
    }

    @Test
    void installingNeverRunsPluginCode() throws Exception {
        Path marker = build.resolve("static-initializer-ran");
        Path jar = TestPlugins.jar(build, TestPlugins.manifest("marker", "1.0.0", "test.marker.Plugin",
                "[{\"name\":\"marker_ping\"}]", "[]"), Map.of("test.marker.Plugin", """
                package test.marker;

                import com.example.agent.plugin.api.AgentPlugin;
                import com.example.agent.plugin.api.PluginContext;
                import org.springframework.ai.tool.annotation.Tool;

                public class Plugin implements AgentPlugin {
                    static {
                        try {
                            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("plugin.test.marker")), "ran");
                        } catch (java.io.IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }

                    @Override
                    public void start(PluginContext context) {
                        context.registerTools(new Tools());
                    }

                    public static class Tools {
                        @Tool(name = "marker_ping", description = "Answers pong")
                        public String ping() {
                            return "pong";
                        }
                    }
                }
                """));
        System.setProperty("plugin.test.marker", marker.toString());
        try {
            host.install(jar);
            assertThat(marker).as("installing must not initialize the entrypoint").doesNotExist();

            host.manager.enable("marker", "1.0.0");
            assertThat(marker).as("enabling initializes and starts it").hasContent("ran");
        } finally {
            System.clearProperty("plugin.test.marker");
        }
    }

    @Test
    void keepsAnInstalledVersionWhenADifferentJarClaimsIt() throws Exception {
        Path original = TestPlugins.echoJar(build, "acme", "1.0.0", false, "[]");
        Path changed = TestPlugins.echoJar(build, "acme", "1.0.0", true, "[]");
        install(original, null);

        assertRefused(() -> install(changed, null), PluginException.Code.VERSION_EXISTS, "different SHA-256");
        assertThat(root.resolve("acme/versions/1.0.0/plugin.jar")).hasSameBinaryContentAs(original);
    }

    @Test
    void refusesBadUploadsWithoutLeavingFiles() throws Exception {
        Path jar = TestPlugins.echoJar(build, "acme", "1.0.0", false, "[]");

        assertRefused(() -> install(jar, "0".repeat(64)), PluginException.Code.SHA256_MISMATCH, "not the expected");
        assertRefused(() -> install(jar, "not-a-digest"), PluginException.Code.INVALID_REQUEST, "64 hexadecimal");
        Path broken = TestPlugins.jar(build, "{\"api\":1}", Map.of());
        assertRefused(() -> install(broken, null), PluginException.Code.INVALID_MANIFEST, "id is required");
        assertRefused(() -> host.installer.install(new ByteArrayInputStream(new byte[0]), null),
                PluginException.Code.INVALID_MANIFEST, "not a JAR file");
        try (var small = new TestPluginHost(root, DataSize.ofBytes(Files.size(jar) - 1), Duration.ofMinutes(5));
             InputStream upload = Files.newInputStream(jar)) {
            assertRefused(() -> small.installer.install(upload, null), PluginException.Code.JAR_TOO_LARGE, "max-jar-size");
        }

        assertThat(host.store.entries()).isEmpty();
        assertThat(root.resolve("acme")).doesNotExist();
        assertThat(root.resolve(".staging")).isEmptyDirectory();
    }

    @Test
    void aMisspelledConfirmationFlagFailsTheInstallInsteadOfBeingIgnored() {
        Path jar = TestPlugins.jar(build, TestPlugins.manifest("acme", "1.0.0", "test.acme.Plugin",
                "[{\"name\":\"acme_echo\",\"requireConfirmation\":true},{\"name\":\"acme_write\"}]", "[]"),
                Map.of("test.acme.Plugin", TestPlugins.echoPlugin("acme")));

        assertRefused(() -> install(jar, null), PluginException.Code.INVALID_MANIFEST,
                "unknown field tools[0].requireConfirmation");
    }

    private PluginInstaller.Result install(Path jar, String sha256) throws IOException {
        try (InputStream upload = Files.newInputStream(jar)) {
            return host.installer.install(upload, sha256);
        }
    }

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                      PluginException.Code code, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PluginException.class,
                e -> assertThat(e.code()).isEqualTo(code)).hasMessageContaining(message);
    }

    static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
