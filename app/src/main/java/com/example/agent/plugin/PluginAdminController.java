package com.example.agent.plugin;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 插件管理接口 {@code /admin/plugins}。示例没有做鉴权：这个接口能让服务进程加载并运行任意 Java 代码，
 * 真实部署时必须只对管理员开放（由网关或上游服务保护）。
 */
@RestController
@RequestMapping(value = "/admin/plugins", produces = MediaType.APPLICATION_JSON_VALUE)
public class PluginAdminController {

    private final PluginManager plugins;
    private final PluginInstaller installer;

    public PluginAdminController(PluginManager plugins, PluginInstaller installer) {
        this.plugins = plugins;
        this.installer = installer;
    }

    /** 只安装不启用：新版本返回 201，相同版本已安装返回 200。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PluginView> install(@RequestPart("file") MultipartFile file,
                                              @RequestParam(required = false) String sha256) throws IOException {
        try (InputStream upload = file.getInputStream()) {
            PluginInstaller.Result result = installer.install(upload, sha256);
            return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.plugin());
        }
    }

    @GetMapping
    public List<PluginView> list() {
        return plugins.list();
    }

    @GetMapping("/{id}")
    public PluginView view(@PathVariable String id) {
        return plugins.view(id);
    }

    /** 启用或切换到某个已安装的版本；回滚也用它，再启用旧版本即可。 */
    @PostMapping(value = "/{id}/enable", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PluginView enable(@PathVariable String id, @RequestBody EnableRequest request) {
        if (request.version() == null || request.version().isBlank()) {
            throw new PluginException(PluginException.Code.INVALID_REQUEST, "version is required");
        }
        return plugins.enable(id, request.version());
    }

    @PostMapping("/{id}/reload")
    public PluginView reload(@PathVariable String id) {
        return plugins.reload(id);
    }

    @PostMapping("/{id}/disable")
    public PluginView disable(@PathVariable String id) {
        return plugins.disable(id);
    }

    /** 整体替换配置值和额外的审批名单：配置在下一次启动（启用、重载）时生效，审批名单立即生效。 */
    @PutMapping(value = "/{id}/config", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PluginView configure(@PathVariable String id, @RequestBody ConfigRequest request) {
        return plugins.configure(id, request.values(), request.confirmTools());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> uninstall(@PathVariable String id, @RequestParam(defaultValue = "false") boolean purgeData) {
        plugins.uninstall(id, purgeData);
        return ResponseEntity.noContent().build();
    }

    public record EnableRequest(@Nullable String version) {}

    public record ConfigRequest(@Nullable Map<String, String> values, @Nullable List<String> confirmTools) {}
}
