package com.example.agent.plugin;

import org.springframework.http.HttpStatus;

import java.util.Locale;

/** 带稳定错误码的插件操作失败；接口以 {@code {"code","message"}} 返回。 */
public final class PluginException extends RuntimeException {

    public enum Code {
        INVALID_REQUEST(HttpStatus.BAD_REQUEST),
        INVALID_MANIFEST(HttpStatus.BAD_REQUEST),
        SHA256_MISMATCH(HttpStatus.BAD_REQUEST),
        JAR_TOO_LARGE(HttpStatus.BAD_REQUEST),
        PLUGIN_NOT_FOUND(HttpStatus.NOT_FOUND),
        VERSION_NOT_FOUND(HttpStatus.NOT_FOUND),
        VERSION_EXISTS(HttpStatus.CONFLICT),
        PLUGIN_RUNNING(HttpStatus.CONFLICT),
        PLUGIN_DISABLED(HttpStatus.CONFLICT),
        RUNTIME_RETIRING(HttpStatus.CONFLICT),
        START_FAILED(HttpStatus.UNPROCESSABLE_CONTENT);

        private final HttpStatus status;

        Code(HttpStatus status) {
            this.status = status;
        }

        public HttpStatus status() { return status; }

        /** 接口里的写法，例如 {@code start_failed}。 */
        public String value() { return name().toLowerCase(Locale.ROOT); }
    }

    private final Code code;

    public PluginException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() { return code; }
}
