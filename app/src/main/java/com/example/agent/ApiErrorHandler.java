package com.example.agent;

import com.example.agent.plugin.PluginException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

/** 统一的错误响应体：{@code {"code":"start_failed","message":"..."}}。前端按 code 分支，message 给人看。 */
@RestControllerAdvice
class ApiErrorHandler {

    record ErrorBody(String code, String message) {}

    @ExceptionHandler(PluginException.class)
    ResponseEntity<ErrorBody> refused(PluginException e) {
        return error(e.code().status(), e.code().value(), e.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorBody> status(ResponseStatusException e) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        String code = status == HttpStatus.BAD_REQUEST ? PluginException.Code.INVALID_REQUEST.value() : status.name().toLowerCase(Locale.ROOT);
        return error(status, code, e.getReason() == null ? status.getReasonPhrase() : e.getReason());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorBody> tooLarge(MaxUploadSizeExceededException e) {
        return error(HttpStatus.BAD_REQUEST, PluginException.Code.JAR_TOO_LARGE.value(),
                "The upload exceeds the multipart size limit");
    }

    @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<ErrorBody> malformed(Exception e) {
        return error(HttpStatus.BAD_REQUEST, PluginException.Code.INVALID_REQUEST.value(),
                "Missing or malformed request: " + e.getMessage());
    }

    /**
     * 显式声明 JSON：SSE 客户端会带 {@code Accept: text/event-stream}，如果按它协商，错误体写不出来，400 会变成 500。
     * 预先设好的 Content-Type 会被 Spring 直接采用。
     */
    private static ResponseEntity<ErrorBody> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorBody(code, message));
    }
}
