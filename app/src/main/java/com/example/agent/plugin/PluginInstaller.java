package com.example.agent.plugin;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 安装上传的插件 JAR，全程不执行插件代码：边写暂存文件边计数、边算 SHA-256，读取并校验清单，
 * 再把文件原子移入不可变的版本目录。JAR 里的类一个都不加载——那要等到管理员启用某个版本时才发生。
 */
@Service
public class PluginInstaller {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final PluginStore store;
    private final PluginManager manager;
    private final PluginProperties properties;

    public PluginInstaller(PluginStore store, PluginManager manager, PluginProperties properties) {
        this.store = store;
        this.manager = manager;
        this.properties = properties;
    }

    /** @param created 相同版本已经安装过时为 false */
    public record Result(boolean created, PluginView plugin) {}

    /** @param expectedSha256 可选：上传内容必须匹配的十六进制摘要 */
    public Result install(InputStream upload, @Nullable String expectedSha256) {
        String expected = expectedSha256 == null || expectedSha256.isBlank() ? null : expectedSha256.strip().toLowerCase(Locale.ROOT);
        if (expected != null && !SHA256.matcher(expected).matches()) {
            throw new PluginException(PluginException.Code.INVALID_REQUEST, "sha256 must be 64 hexadecimal characters");
        }
        Path staged = null;
        try {
            staged = store.newStagingFile();
            String sha256 = copy(upload, staged, properties.maxJarSize().toBytes());
            if (expected != null && !expected.equals(sha256)) {
                throw new PluginException(PluginException.Code.SHA256_MISMATCH,
                        "The upload's SHA-256 is " + sha256 + ", not the expected " + expected);
            }
            PluginManifest manifest = PluginManifest.read(staged);
            boolean created = store.addVersion(manifest, staged, sha256);
            return new Result(created, manager.view(manifest.id()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to stage the plugin upload", e);
        } finally {
            if (staged != null) deleteQuietly(staged);
        }
    }

    private static String copy(InputStream upload, Path target, long limit) throws IOException {
        MessageDigest digest = sha256();
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(target)) {
            for (int read; (read = upload.read(buffer)) != -1; ) {
                total += read;
                if (total > limit) {
                    throw new PluginException(PluginException.Code.JAR_TOO_LARGE,
                            "The plugin JAR exceeds plugins.max-jar-size (" + limit + " bytes)");
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    private static void deleteQuietly(Path staged) {
        try {
            Files.deleteIfExists(staged);
        } catch (IOException ignored) {
            // 暂存区在存储首次使用时会被清空。
        }
    }
}
