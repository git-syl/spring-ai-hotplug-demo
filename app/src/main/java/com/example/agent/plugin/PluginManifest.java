package com.example.agent.plugin;

import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 插件 JAR 中经过校验的 {@code META-INF/agent-plugin.json}。读取时只把 JAR 当作 ZIP 文件打开，绝不从中加载类。
 * 未知字段、重复键和类型不符一律拒绝而不是忽略：拼错的 {@code requiresConfirmation} 不能悄悄让写工具失去审批。
 */
record PluginManifest(String id, String version, String name, String description, String entrypoint,
                      List<Tool> tools, List<ConfigKey> config) {

    static final String PATH = "META-INF/agent-plugin.json";
    /** 宿主实现的唯一契约版本。 */
    static final int API = 1;

    private static final int MAX_BYTES = 64 * 1024;
    /** OpenAI 兼容接口的函数名长度上限，对所有模型提供方统一使用。 */
    private static final int MAX_TOOL_NAME = 64;
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{1,31}");
    /** 插件 ID 会成为目录名，这些名字在 Windows 上是设备名，不能用作目录。 */
    private static final Pattern WINDOWS_DEVICE = Pattern.compile("con|prn|aux|nul|com[1-9]|lpt[1-9]");
    private static final Pattern VERSION = Pattern.compile(
            "(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?");
    private static final Pattern TOOL_SUFFIX = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*");
    private static final Pattern CONFIG_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,63}");
    private static final String HOST_PACKAGE = "com.example.agent.";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    record Tool(String name, boolean requiresConfirmation) {}

    record ConfigKey(String key, String description, boolean required, boolean secret, @Nullable String defaultValue) {}

    PluginManifest {
        tools = List.copyOf(tools);
        config = List.copyOf(config);
    }

    /** 读取并校验清单，再确认入口类的 class 文件确实在 JAR 里——只看 ZIP 条目，不加载类。 */
    static PluginManifest read(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(PATH);
            if (entry == null) throw invalid("the JAR has no " + PATH);
            byte[] json;
            try (InputStream in = zip.getInputStream(entry)) {
                json = in.readNBytes(MAX_BYTES + 1);
            }
            if (json.length > MAX_BYTES) throw invalid(PATH + " exceeds 64KB");
            PluginManifest manifest = parse(json);
            if (zip.getEntry(manifest.entrypoint().replace('.', '/') + ".class") == null) {
                throw invalid("entrypoint class " + manifest.entrypoint() + " is not in the JAR");
            }
            return manifest;
        } catch (ZipException e) {
            throw invalid("the upload is not a JAR file");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read plugin JAR " + jar, e);
        }
    }

    static PluginManifest parse(byte[] json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException e) {
            throw invalid("malformed JSON: " + e.getOriginalMessage());
        }
        ObjectNode manifest = object(root, "manifest");
        allowFields(manifest, "", "api", "id", "version", "name", "description", "entrypoint", "tools", "config");
        JsonNode api = manifest.get("api");
        if (api == null || !api.isInt() || api.intValue() != API) throw invalid("api must be the integer " + API);

        String id = text(manifest, "", "id", true);
        if (!ID.matcher(id).matches()) throw invalid("id must match " + ID.pattern());
        if (WINDOWS_DEVICE.matcher(id).matches()) throw invalid("id must not be a reserved device name: " + id);
        String version = text(manifest, "", "version", true);
        if (version.length() > 64 || !VERSION.matcher(version).matches()) {
            throw invalid("version must be MAJOR.MINOR.PATCH with an optional pre-release suffix, at most 64 characters");
        }
        String entrypoint = text(manifest, "", "entrypoint", true);
        if (!CLASS_NAME.matcher(entrypoint).matches()) throw invalid("entrypoint must be a fully qualified class name");
        if (entrypoint.startsWith(HOST_PACKAGE)) throw invalid("entrypoint must not be in the host package " + HOST_PACKAGE);
        return new PluginManifest(id, version, text(manifest, "", "name", true), text(manifest, "", "description", true),
                entrypoint, tools(manifest.get("tools"), id), config(manifest.get("config")));
    }

    Set<String> toolNames() {
        Set<String> names = new LinkedHashSet<>();
        tools.forEach(tool -> names.add(tool.name()));
        return names;
    }

    boolean declaresTool(String name) {
        return tools.stream().anyMatch(tool -> tool.name().equals(name));
    }

    boolean requiresConfirmation(String tool) {
        return tools.stream().anyMatch(declared -> declared.name().equals(tool) && declared.requiresConfirmation());
    }

    Optional<ConfigKey> configKey(String key) {
        return config.stream().filter(declared -> declared.key().equals(key)).findFirst();
    }

    /** 例如 {@code webdom@1.0.0}，也是插件类加载器的名字。 */
    String label() {
        return id + "@" + version;
    }

    private static List<Tool> tools(@Nullable JsonNode node, String id) {
        if (node == null || !node.isArray() || node.isEmpty()) throw invalid("tools must be a non-empty array");
        List<Tool> tools = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < node.size(); i++) {
            String path = "tools[" + i + "]";
            ObjectNode tool = object(node.get(i), path);
            allowFields(tool, path, "name", "requiresConfirmation");
            String name = text(tool, path, "name", true);
            String prefix = id + "_";
            if (!name.startsWith(prefix) || !TOOL_SUFFIX.matcher(name.substring(prefix.length())).matches()) {
                throw invalid(path + ".name must be '" + prefix + "' followed by letters, digits, '_' or '-'");
            }
            if (name.length() > MAX_TOOL_NAME) throw invalid(path + ".name exceeds " + MAX_TOOL_NAME + " characters");
            if (!names.add(name)) throw invalid("duplicate tool name " + name);
            tools.add(new Tool(name, flag(tool, path, "requiresConfirmation")));
        }
        return tools;
    }

    private static List<ConfigKey> config(@Nullable JsonNode node) {
        if (node == null) return List.of();
        if (!node.isArray()) throw invalid("config must be an array");
        List<ConfigKey> keys = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < node.size(); i++) {
            String path = "config[" + i + "]";
            ObjectNode key = object(node.get(i), path);
            allowFields(key, path, "key", "description", "required", "secret", "default");
            String name = text(key, path, "key", true);
            if (!CONFIG_KEY.matcher(name).matches()) throw invalid(path + ".key must match " + CONFIG_KEY.pattern());
            if (!names.add(name)) throw invalid("duplicate config key " + name);
            String description = text(key, path, "description", false);
            keys.add(new ConfigKey(name, description == null ? "" : description, flag(key, path, "required"),
                    flag(key, path, "secret"), text(key, path, "default", false)));
        }
        return keys;
    }

    private static ObjectNode object(@Nullable JsonNode node, String path) {
        if (node instanceof ObjectNode object) return object;
        throw invalid((path.isEmpty() ? "manifest" : path) + " must be a JSON object");
    }

    private static void allowFields(ObjectNode node, String path, String... allowed) {
        Set<String> names = Set.of(allowed);
        for (String field : node.propertyNames()) {
            if (!names.contains(field)) throw invalid("unknown field " + label(path, field));
        }
    }

    private static @Nullable String text(ObjectNode node, String path, String field, boolean required) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            if (required) throw invalid(label(path, field) + " is required");
            return null;
        }
        if (!value.isString()) throw invalid(label(path, field) + " must be a string");
        if (required && value.stringValue().isBlank()) throw invalid(label(path, field) + " must not be blank");
        return value.stringValue();
    }

    private static boolean flag(ObjectNode node, String path, String field) {
        JsonNode value = node.get(field);
        if (value == null) return false;
        if (!value.isBoolean()) throw invalid(label(path, field) + " must be true or false");
        return value.booleanValue();
    }

    private static String label(String path, String field) {
        return path.isEmpty() ? field : path + "." + field;
    }

    private static PluginException invalid(String message) {
        return new PluginException(PluginException.Code.INVALID_MANIFEST, "Invalid plugin manifest: " + message);
    }
}
