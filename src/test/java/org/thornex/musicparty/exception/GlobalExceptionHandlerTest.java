package org.thornex.musicparty.exception;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 内部错误码只用于程序判断，回给前端的 message 要干净。 */
class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(String message) {
        return (Map<String, Object>) handler.handleApiRequestException(new ApiRequestException(message)).getBody();
    }

    @Test
    void internalCodeIsMovedOutOfTheUserFacingMessage() {
        var body = body("SOURCE_AUTH_REQUIRED: 网易云音乐 Cookie 无效或已过期");
        assertEquals("网易云音乐 Cookie 无效或已过期", body.get("message"));
        assertEquals("SOURCE_AUTH_REQUIRED", body.get("code"));
    }

    @Test
    void plainCodesWithoutAColonStayUntouched() {
        // 前端/服务端按整串判断的哨兵值不能被改写。
        assertEquals("MEDIA_CONTENT_CHANGED", body("MEDIA_CONTENT_CHANGED").get("message"));
        assertNull(body("MEDIA_CONTENT_CHANGED").get("code"));
    }

    @Test
    void messagesWithoutACodeStayUntouched() {
        assertEquals("歌单不可读取", body("歌单不可读取").get("message"));
        assertEquals("请求失败", body("").get("message"));
    }
}
