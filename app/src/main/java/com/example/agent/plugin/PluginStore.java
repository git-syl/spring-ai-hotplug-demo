package com.example.agent.plugin;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code plugins.root} 目录的唯一读写入口：{@code registry.json}、不可变的 {@code <id>/versions/<version>/plugin.jar}、
 * {@code <id>/data} 数据目录和上传暂存区 {@code .staging}。
 *
 * <p>读取使用不可变快照，不加锁。写入串行执行：先写临时文件，再原子替换 registry.json，成功后才发布新快照。</p>
 */
@Component
public class PluginStore {

    private static final Logger log = LoggerFactory.getLogger(PluginStore.class);
    private static final String REGISTRY = "registry.json";
    static final String STAGING = ".staging";
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final Path root;
    /** 已安装版本的清单。版本的 JAR 不会再变，所以它的清单也不会变，可以一直缓存。 */
    private final Map<String, PluginManifest> manifests = new ConcurrentHashMap<>();
    private volatile @Nullable Map<String, Entry> entries;

    public PluginStore(PluginProperties properties) {
        this.root = properties.root().toAbsolutePath().normalize();
    }

    /** registry.json 里的一个已安装插件；versions 按安装顺序排列。 */
    record Entry(String id, @Nullable String enabledVersion, Map<String, String> config, List<String> confirmTools,
                 List<Version> versions) {

        Entry {
            config = config == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(config));
            confirmTools = confirmTools == null ? List.of() : List.copyOf(confirmTools);
            versions = versions == null ? List.of() : List.copyOf(versions);
        }

        Optional<Version> version(String version) {
            return versions.stream().filter(installed -> installed.version().equals(version)).findFirst();
        }

        Version latest() {
            return versions.getLast();
        }
    }

    record Version(String version, String sha256, String installedAt) {}

    record Registry(List<Entry> plugins) {}

    List<Entry> entries() {
        return List.copyOf(loaded().values());
    }

    Optional<Entry> entry(String id) {
        return Optional.ofNullable(loaded().get(id));
    }

    Path jar(String id, String version) {
        return root.resolve(id).resolve("versions").resolve(version).resolve("plugin.jar");
    }

    Path dataDirectory(String id) {
        return root.resolve(id).resolve("data");
    }

    PluginManifest manifest(String id, String version) {
        return manifests.computeIfAbsent(id + "@" + version, key -> PluginManifest.read(jar(id, version)));
    }

    /** 暂存区里的一个新空文件；上一个进程遗留的上传在首次使用时清空。 */
    Path newStagingFile() throws IOException {
        loaded();
        return Files.createTempFile(Files.createDirectories(root.resolve(STAGING)), "upload-", ".jar");
    }

    /**
     * 把暂存的 JAR 发布为一个新的不可变版本。再次安装内容完全相同的版本什么也不做。
     *
     * @return 是否新增了版本
     */
    synchronized boolean addVersion(PluginManifest manifest, Path staged, String sha256) {
        Entry entry = loaded().get(manifest.id());
        Optional<Version> existing = entry == null ? Optional.empty() : entry.version(manifest.version());
        if (existing.isPresent()) {
            if (existing.get().sha256().equals(sha256)) return false;
            throw new PluginException(PluginException.Code.VERSION_EXISTS, "Plugin " + manifest.label()
                    + " is already installed with a different SHA-256 (" + existing.get().sha256() + ")");
        }
        Path target = jar(manifest.id(), manifest.version());
        try {
            // 没有登记记录的文件来自一次被中断的安装，直接替换。
            Files.deleteIfExists(target);
            Files.createDirectories(target.getParent());
            move(staged, target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to publish plugin " + manifest.label(), e);
        }
        List<Version> versions = new ArrayList<>(entry == null ? List.of() : entry.versions());
        versions.add(new Version(manifest.version(), sha256, Instant.now().toString()));
        try {
            save(entry == null
                    ? new Entry(manifest.id(), null, Map.of(), List.of(), versions)
                    : new Entry(entry.id(), entry.enabledVersion(), entry.config(), entry.confirmTools(), versions));
        } catch (RuntimeException e) {
            deleteQuietly(target.getParent());
            throw e;
        }
        manifests.put(manifest.label(), manifest);
        return true;
    }

    synchronized void setEnabledVersion(String id, @Nullable String version) {
        Entry entry = existing(id);
        save(new Entry(id, version, entry.config(), entry.confirmTools(), entry.versions()));
    }

    synchronized void setConfig(String id, Map<String, String> values, List<String> confirmTools) {
        Entry entry = existing(id);
        save(new Entry(id, entry.enabledVersion(), values, confirmTools, entry.versions()));
    }

    /** 删除登记和全部版本；数据目录默认保留。 */
    synchronized void remove(String id, boolean purgeData) {
        existing(id);
        Map<String, Entry> remaining = new LinkedHashMap<>(loaded());
        remaining.remove(id);
        write(remaining);
        manifests.keySet().removeIf(key -> key.startsWith(id + "@"));
        deleteQuietly(purgeData ? root.resolve(id) : root.resolve(id).resolve("versions"));
    }

    private Entry existing(String id) {
        Entry entry = loaded().get(id);
        if (entry == null) throw new PluginException(PluginException.Code.PLUGIN_NOT_FOUND, "Plugin not found: " + id);
        return entry;
    }

    private void save(Entry entry) {
        Map<String, Entry> updated = new LinkedHashMap<>(loaded());
        updated.put(entry.id(), entry);
        write(updated);
    }

    private void write(Map<String, Entry> updated) {
        try {
            Files.createDirectories(root);
            Path temporary = Files.createTempFile(root, REGISTRY, ".tmp");
            try {
                try (OutputStream out = Files.newOutputStream(temporary)) {
                    JSON.writeValue(out, new Registry(List.copyOf(updated.values())));
                }
                move(temporary, root.resolve(REGISTRY));
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write the plugin registry", e);
        }
        entries = Collections.unmodifiableMap(updated);
    }

    private Map<String, Entry> loaded() {
        Map<String, Entry> snapshot = entries;
        if (snapshot != null) return snapshot;
        synchronized (this) {
            if (entries == null) entries = load();
            return entries;
        }
    }

    private Map<String, Entry> load() {
        deleteQuietly(root.resolve(STAGING));
        Path registry = root.resolve(REGISTRY);
        if (!Files.exists(registry)) return Map.of();
        try (InputStream in = Files.newInputStream(registry)) {
            Map<String, Entry> loaded = new LinkedHashMap<>();
            for (Entry entry : JSON.readValue(in, Registry.class).plugins()) loaded.put(entry.id(), entry);
            return Collections.unmodifiableMap(loaded);
        } catch (IOException | JacksonException e) {
            throw new IllegalStateException("Unreadable plugin registry " + registry + ": " + e.getMessage(), e);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 删不掉的残留无害：被中断的安装会被下一次安装替换，也没有任何地方会列出它们。 */
    private static void deleteQuietly(Path path) {
        if (!Files.exists(path)) return;
        try (var paths = Files.walk(path)) {
            for (Path each : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(each);
        } catch (IOException | UncheckedIOException e) {
            log.warn("plugin.store.delete-failed path={} error={}", path, e.getMessage());
        }
    }
}
