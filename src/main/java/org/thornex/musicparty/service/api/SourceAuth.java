package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.thornex.musicparty.exception.ApiRequestException;

/**
 * 音源「Cookie 无效 / 未登录」的统一识别与表述.
 *
 * <p>各上游的鉴权失败信号不一样：网易云用业务码（301 = 需要登录、-462 = 需要验证），
 * B 站用 {@code -101}（账号未登录），QQ 音乐用 HTTP 401/403。这里统一收敛成
 * {@link #CODE} 前缀，前端与系统消息可以据此提示用户去管理员面板更新 Cookie。</p>
 */
public final class SourceAuth {
    public static final String CODE = "SOURCE_AUTH_REQUIRED";

    private SourceAuth() { }

    /** 构造「该源 Cookie 无效」的明确错误。 */
    public static ApiRequestException invalid(String sourceLabel) {
        return new ApiRequestException(CODE + ": " + sourceLabel + " Cookie 无效、已过期或未配置，请在管理员面板更新后重试");
    }

    /** 上游以业务码表示需要登录；缺省（没有 code 字段）视为正常响应。 */
    public static boolean requiresLogin(JsonNode json) {
        if (json == null) return false;
        int code = json.path("code").asInt(200);
        return code == 301 || code == -101 || code == -462 || code == 401;
    }

    /** 给用户看的文案：去掉内部错误码前缀，其它错误原样返回。 */
    public static String userMessage(String message) {
        if (message == null) return null;
        return message.replaceFirst("^" + CODE + ":\\s*", "");
    }
}
