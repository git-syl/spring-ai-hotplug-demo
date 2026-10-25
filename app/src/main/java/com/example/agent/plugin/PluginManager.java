package com.example.agent.plugin;

import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 插件运行状态的唯一来源：服务就绪后启动已启用的版本；负责启用、切换、重载、停用和退役运行实例；为对话请求发放租约。
 *
 * <p>同一个插件的管理操作在它自己的 {@link Slot} 上串行执行；{@link #acquire} 只读取当前实例的引用，从不等待管理操作。
 * 新实例先启动、成功后才替换当前实例，所以启动失败时旧实例照常服务。被替换的实例进入退役：不再发放新租约，
 * 等它的租约和在途调用都结束（或 {@code plugins.retire-timeout} 到期）后停止。</p>
 */
@Service
public class PluginManager {

    /** 密钥配置在响应里的样子；保存配置时原样发回它表示保留原值。 */
    static final String MASK = "******";

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final PluginStore store;
    private final PluginProperties properties;
    private final Function<String, String> environment;
    private final ConcurrentMap<String, Slot> slots = new ConcurrentHashMap<>();

    @Autowired
    public PluginManager(PluginStore store, PluginProperties properties) {
        this(store, properties, System::getenv);
    }

    PluginManager(PluginStore store, PluginProperties properties, Function<String, String> environment) {
        this.store = store;
        this.properties = properties;
        this.environment = environment;
    }

    /** 一个插件的运行实例。Slot 从不删除，所以对同一个插件的所有操作锁的都是同一个对象。 */
    private static final class Slot {
        volatile @Nullable PluginRuntime current;
        final Set<PluginRuntime> retiring = ConcurrentHashMap.newKeySet();
        volatile @Nullable String lastError;
    }

    /** 启动失败的插件记为 FAILED，服务照常可用。 */
    @EventListener(ApplicationReadyEvent.class)
    public void startEnabledPlugins() {
        List<PluginStore.Entry> entries;
        try {
            entries = store.entries();
        } catch (RuntimeException e) {
            log.error("plugin.registry.unreadable error={}", e.getMessage(), e);
            return;
        }
        for (PluginStore.Entry entry : entries) {
            if (entry.enabledVersion() == null) continue;
            Slot slot = slot(entry.id());
            synchronized (slot) {
                if (slot.current != null) continue;
                try {
                    slot.current = start(slot, entry, entry.enabledVersion());
                } catch (PluginException e) {
                    // start() 已记下原因；在修好之前，点名这个插件的请求都会得到 400。
                }
            }
        }
    }

    @PreDestroy
    public void stopAll() {
        for (Slot slot : slots.values()) {
            PluginRuntime current = slot.current;
            slot.current = null;
            if (current != null) current.stop();
            slot.retiring.forEach(PluginRuntime::stop);
        }
    }

    /**
     * 建立 SSE 之前的校验：ID 不为空、不重复，且插件正在运行。只读取状态，不取租约。
     *
     * @throws PluginException {@code INVALID_REQUEST}，接口返回 400
     */
    public List<String> check(@Nullable List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            if (id == null || !seen.add(id)) throw invalidRequest("plugins contains a null or duplicate ID: " + id);
            Slot slot = slots.get(id);
            if (slot == null || slot.current == null) throw invalidRequest(notRunning(id));
        }
        return List.copyOf(ids);
    }

    /**
     * 为一次对话请求租用所列插件的当前运行实例。请求结束时关闭返回的租约。
     *
     * @throws PluginException {@code INVALID_REQUEST}：ID 为空或重复、插件不存在或没有运行
     */
    public PluginLease acquire(@Nullable List<String> ids) {
        if (ids == null || ids.isEmpty()) return PluginLease.none();
        Set<String> seen = new HashSet<>();
        List<PluginRuntime> leased = new ArrayList<>();
        try {
            for (String id : ids) {
                if (id == null || !seen.add(id)) throw invalidRequest("plugins contains a null or duplicate ID: " + id);
                leased.add(lease(id));
            }
            return new PluginLease(leased);
        } catch (RuntimeException e) {
            leased.forEach(PluginRuntime::releaseLease);
            throw e;
        }
    }

    /**
     * 这个工具调用是否需要人工审批：任一存活实例的清单声明了，或管理员的 {@code confirmTools} 列出了它。
     * 退役中的实例也算，所以由旧版本执行的调用，保留旧版本声明的审批要求。
     */
    boolean requiresConfirmation(String toolName) {
        int separator = toolName.indexOf('_');
        Slot slot = separator > 0 ? slots.get(toolName.substring(0, separator)) : null;
        if (slot == null) return false;
        return live(slot).anyMatch(runtime -> runtime.manifest().requiresConfirmation(toolName))
                || store.entry(toolName.substring(0, separator)).map(entry -> entry.confirmTools().contains(toolName)).orElse(false);
    }

    public List<PluginView> list() {
        return store.entries().stream().map(this::describe).toList();
    }

    public PluginView view(String id) {
        return describe(entry(id));
    }

    /** 先启动这个版本，再用它替换当前实例；该版本已经在运行时什么也不做。 */
    public PluginView enable(String id, String version) {
        return locked(id, (slot, entry) -> {
            if (entry.version(version).isEmpty()) {
                throw new PluginException(PluginException.Code.VERSION_NOT_FOUND, "Plugin " + id + " has no version " + version);
            }
            PluginRuntime current = slot.current;
            if (current == null || !current.version().equals(version)) {
                PluginRuntime started = start(slot, entry, version);
                try {
                    store.setEnabledVersion(id, version);
                } catch (RuntimeException e) {
                    started.stop();
                    throw e;
                }
                replace(slot, started);
            }
            return describe(entry(id));
        });
    }

    /** 用当前配置重新启动已启用的版本。 */
    public PluginView reload(String id) {
        return locked(id, (slot, entry) -> {
            if (entry.enabledVersion() == null) {
                throw new PluginException(PluginException.Code.PLUGIN_DISABLED, "Plugin " + id + " is disabled; enable a version first");
            }
            replace(slot, start(slot, entry, entry.enabledVersion()));
            return describe(entry);
        });
    }

    public PluginView disable(String id) {
        return locked(id, (slot, entry) -> {
            if (entry.enabledVersion() != null) store.setEnabledVersion(id, null);
            replace(slot, null);
            slot.lastError = null;
            return describe(entry(id));
        });
    }

    /**
     * 整体替换配置值和额外的审批名单。配置值在下一次启动时生效；{@code confirmTools} 立即生效，
     * 因为审批策略每次都实时读取它，而且它只会增加审批。发回 {@value #MASK} 的密钥键保留原值。
     */
    public PluginView configure(String id, @Nullable Map<String, String> values, @Nullable List<String> confirmTools) {
        return locked(id, (slot, entry) -> {
            List<PluginManifest> manifests = entry.versions().stream()
                    .map(version -> store.manifest(id, version.version())).toList();
            Map<String, String> stored = new LinkedHashMap<>();
            for (Map.Entry<String, String> value : (values == null ? Map.<String, String>of() : values).entrySet()) {
                String key = value.getKey();
                List<PluginManifest.ConfigKey> declared = manifests.stream().flatMap(m -> m.configKey(key).stream()).toList();
                if (declared.isEmpty()) throw invalidRequest("Plugin " + id + " declares no config key " + key);
                if (value.getValue() == null) continue;
                if (MASK.equals(value.getValue()) && declared.stream().anyMatch(PluginManifest.ConfigKey::secret)) {
                    if (entry.config().containsKey(key)) stored.put(key, entry.config().get(key));
                } else {
                    stored.put(key, value.getValue());
                }
            }
            List<String> tools = confirmTools == null ? List.of() : confirmTools.stream().distinct().toList();
            for (String tool : tools) {
                if (tool == null || manifests.stream().noneMatch(manifest -> manifest.declaresTool(tool))) {
                    throw invalidRequest("Plugin " + id + " declares no tool " + tool);
                }
            }
            store.setConfig(id, stored, tools);
            return describe(entry(id));
        });
    }

    /** 卸载已停用的插件及其全部版本；数据目录默认保留。 */
    public void uninstall(String id, boolean purgeData) {
        locked(id, (slot, entry) -> {
            if (entry.enabledVersion() != null || slot.current != null) {
                throw new PluginException(PluginException.Code.PLUGIN_RUNNING, "Plugin " + id + " is enabled; disable it first");
            }
            if (!slot.retiring.isEmpty()) {
                throw new PluginException(PluginException.Code.RUNTIME_RETIRING,
                        "Plugin " + id + " still has a retiring runtime; retry once its requests have finished");
            }
            store.remove(id, purgeData);
            slot.lastError = null;
            return null;
        });
    }

    private <T> T locked(String id, BiFunction<Slot, PluginStore.Entry, T> operation) {
        entry(id); // 未知 ID 直接 404，不留下空的 Slot。
        Slot slot = slot(id);
        synchronized (slot) {
            return operation.apply(slot, entry(id));
        }
    }

    private PluginRuntime start(Slot slot, PluginStore.Entry entry, String version) {
        try {
            PluginRuntime runtime = PluginRuntime.start(store.manifest(entry.id(), version), store.jar(entry.id(), version),
                    entry.config(), environment, store.dataDirectory(entry.id()));
            slot.lastError = null;
            log.info("plugin.started plugin={} tools={}", runtime.label(), runtime.manifest().toolNames());
            return runtime;
        } catch (PluginException | UncheckedIOException e) {
            slot.lastError = e.getMessage();
            log.error("plugin.start.failed plugin={}@{} reason={}", entry.id(), version, e.getMessage());
            throw e instanceof PluginException failure && failure.code() == PluginException.Code.START_FAILED
                    ? failure : new PluginException(PluginException.Code.START_FAILED, e.getMessage());
        }
    }

    private void replace(Slot slot, @Nullable PluginRuntime next) {
        PluginRuntime previous = slot.current;
        slot.current = next;
        if (previous != null) retire(slot, previous);
    }

    private void retire(Slot slot, PluginRuntime runtime) {
        runtime.retire();
        slot.retiring.add(runtime);
        Duration timeout = properties.retireTimeout();
        Thread.ofVirtual().name("plugin-retire-" + runtime.label()).start(() -> {
            try {
                if (!runtime.awaitIdle(timeout)) {
                    log.warn("plugin.retire.forced plugin={} leases={} inFlight={} timeout={}",
                            runtime.label(), runtime.leases(), runtime.inFlight(), timeout);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                // 先停止再移出集合：在任何调用都不可能再执行之前，审批策略都还能看到这个版本的清单。
                runtime.stop();
                slot.retiring.remove(runtime);
                log.info("plugin.stopped plugin={}", runtime.label());
            }
        });
    }

    private PluginRuntime lease(String id) {
        Slot slot = slots.get(id);
        while (true) {
            PluginRuntime runtime = slot == null ? null : slot.current;
            if (runtime == null) throw invalidRequest(notRunning(id));
            if (runtime.acquireLease()) return runtime;
            // 读到它之后它退役了；如果已有替代者，current 现在指向新版本，再读一次。
            if (slot.current == runtime) throw invalidRequest("Plugin " + id + " is not accepting requests");
        }
    }

    private String notRunning(String id) {
        return store.entry(id).map(entry -> "Plugin " + id + " is not running (" + describe(entry).state() + ")")
                .orElse("Unknown plugin: " + id);
    }

    private PluginView describe(PluginStore.Entry entry) {
        Slot slot = slots.get(entry.id());
        PluginRuntime current = slot == null ? null : slot.current;
        PluginView.State state = current != null ? PluginView.State.RUNNING
                : entry.enabledVersion() == null ? PluginView.State.DISABLED : PluginView.State.FAILED;
        List<PluginView.VersionView> versions = entry.versions().stream()
                .map(version -> new PluginView.VersionView(version.version(), version.sha256(), version.installedAt())).toList();
        List<PluginView.RuntimeView> runtimes = slot == null ? List.of() : live(slot)
                .map(runtime -> new PluginView.RuntimeView(runtime.version(), runtime.state().name(), runtime.leases(),
                        runtime.inFlight(), runtime.startedAt().toString())).toList();
        String lastError = slot == null ? null : slot.lastError;
        PluginManifest manifest;
        try {
            manifest = store.manifest(entry.id(), entry.enabledVersion() != null ? entry.enabledVersion() : entry.latest().version());
        } catch (RuntimeException e) {
            return new PluginView(entry.id(), entry.id(), "", state, entry.enabledVersion(), versions, List.of(), List.of(),
                    runtimes, "Unreadable plugin JAR: " + e.getMessage());
        }
        return new PluginView(entry.id(), manifest.name(), manifest.description(), state, entry.enabledVersion(), versions,
                manifest.tools().stream().map(tool ->
                        new PluginView.ToolView(tool.name(), confirms(entry, current, manifest, tool.name()))).toList(),
                manifest.config().stream().map(key -> {
                    String value = entry.config().get(key.key());
                    return new PluginView.ConfigView(key.key(), key.description(), key.required(), key.secret(),
                            value != null && key.secret() ? MASK : value, key.defaultValue());
                }).toList(),
                runtimes, lastError);
    }

    private static boolean confirms(PluginStore.Entry entry, @Nullable PluginRuntime runtime, PluginManifest manifest,
                                    String tool) {
        return manifest.requiresConfirmation(tool) || entry.confirmTools().contains(tool)
                || runtime != null && runtime.tool(tool).map(callback -> callback.getToolMetadata().requiresConfirmation()).orElse(false);
    }

    /** 正在运行和正在退役的实例，最新的在前。 */
    private static Stream<PluginRuntime> live(Slot slot) {
        return Stream.concat(Stream.ofNullable(slot.current), slot.retiring.stream())
                .filter(runtime -> runtime.state() == PluginRuntime.State.RUNNING || runtime.state() == PluginRuntime.State.RETIRING)
                .sorted(Comparator.comparing(PluginRuntime::startedAt).reversed());
    }

    private Slot slot(String id) {
        return slots.computeIfAbsent(id, key -> new Slot());
    }

    private PluginStore.Entry entry(String id) {
        return store.entry(id).orElseThrow(() -> new PluginException(PluginException.Code.PLUGIN_NOT_FOUND, "Plugin not found: " + id));
    }

    private static PluginException invalidRequest(String message) {
        return new PluginException(PluginException.Code.INVALID_REQUEST, message);
    }
}
