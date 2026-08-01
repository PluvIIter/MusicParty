package org.thornex.musicparty.exception;

import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Locale;
import java.util.Map;

@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiRequestException.class)
    public ResponseEntity<Object> handleApiRequestException(ApiRequestException ex) {
        // For failures related to external APIs, return 502 Bad Gateway
        Map<String, Object> body = Map.of(
                "message", ex.getMessage(),
                "status", HttpStatus.BAD_GATEWAY.value()
        );
        return new ResponseEntity<>(body, HttpStatus.BAD_GATEWAY);
    }

    /**
     * 客户端在媒体流传输中主动中断连接（浏览器加载/seek/切歌时取消旧的 Range 请求）。
     * <p>这是媒体播放的正常现象：`<audio>` 加载时通常先发 `Range: bytes=0-` 探测，
     * 随后根据媒体结构取消并重发请求，服务端 Tomcat 在向已断开的连接写入时抛出
     * {@link ClientAbortException}（常包装 `java.io.IOException: Connection reset by peer`）。</p>
     * <p>确认无害后已降为 DEBUG 级别：默认日志（INFO）下完全隐藏；排查时才把日志级别调低可见。</p>
     */
    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbort(ClientAbortException ex) {
        log.debug("客户端中断媒体流连接（浏览器媒体加载/seek/切歌时取消旧 Range 请求，属正常现象）: {}", ex.getMessage());
    }

    /**
     * 静态资源未找到（Spring 6.1 对缺失资源抛出该异常）→ 404。
     * 单独处理避免通用处理器写 JSON 在媒体内容类型下失败。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResourceFound(NoResourceFoundException ex) {
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleGenericException(Exception ex, jakarta.servlet.http.HttpServletResponse response) {
        // 若响应内容类型已被设为媒体/二进制类型（如 audio/mp4）或响应已提交，JSON 错误体无法写入。
        // 此时返回空体，但必须把原始异常记录下来，绝不掩盖真实根因。
        String contentType = response.getContentType();
        if (contentType != null && !contentType.toLowerCase(Locale.ROOT).contains("json")) {
            log.error("请求在内容类型 '{}' 下发生异常，原始异常为: ", contentType, ex);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // For all other unexpected errors, return 500 Internal Server Error
        Map<String, Object> body = Map.of(
                "message", "An unexpected internal server error occurred.",
                "error", ex.getClass().getSimpleName(),
                "status", HttpStatus.INTERNAL_SERVER_ERROR.value()
        );
        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
