package org.thornex.musicparty.resource;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.controller.AuthController;
import org.thornex.musicparty.exception.ApiRequestException;

public final class ResourceAccess {
    private ResourceAccess() {}

    public static void authorize(String supplied, AuthController auth, AppProperties properties) {
        String admin = properties.getAdminPassword();
        if (admin != null && !admin.isBlank() && admin.equals(supplied)) return;
        String room = auth.getRawPassword();
        if (room == null || (!room.isEmpty() && !room.equals(supplied))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请先进入房间");
        }
    }

    public static ResponseEntity<Map<String, Object>> failure(Exception error) {
        String message = error instanceof ApiRequestException ? error.getMessage() : "上游暂时不可用，请稍后重试";
        String code = "UPSTREAM_UNAVAILABLE";
        int status = 502;
        if (error instanceof ResponseStatusException response) {
            status = response.getStatusCode().value();
            code = status == 403 ? "ROOM_ACCESS_REQUIRED" : "INVALID_REQUEST";
            message = response.getReason();
        } else if (error instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
                || error instanceof org.springframework.web.bind.MissingServletRequestParameterException
                || error instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
            status = 400;
            code = "INVALID_REQUEST";
            message = "请求参数格式无效";
        } else if (message != null && message.matches("^[A-Z_]+:.*")) {
            int separator = message.indexOf(':');
            code = message.substring(0, separator);
            message = message.substring(separator + 1).trim();
            status = switch (code) {
                case "SOURCE_NOT_FOUND" -> 404;
                case "SOURCE_DISABLED" -> 409;
                case "INVALID_RESOURCE", "INVALID_CURSOR", "INVALID_LIMIT" -> 400;
                case "COLLECTION_NOT_PUBLIC" -> 403;
                case "CAPABILITY_UNAVAILABLE" -> 422;
                default -> 502;
            };
        }
        if (message == null || message.isBlank()) message = "请求失败，请稍后重试";
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(Map.of("code", code, "message", message, "retryable", status >= 500,
                        "requestId", UUID.randomUUID().toString()));
    }
}
