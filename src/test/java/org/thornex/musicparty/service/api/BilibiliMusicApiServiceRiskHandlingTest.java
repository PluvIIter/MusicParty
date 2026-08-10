package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.thornex.musicparty.exception.ApiRequestException;

import java.net.URI;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B站请求异常分类兜底逻辑的单元测试。
 * <p>覆盖 {@link BilibiliMusicApiService#isRiskControlChallenge}（HTTP 412/403 挑战、正文非 JSON）
 * 与 {@link BilibiliMusicApiService#isNetworkFailure}（DNS/连接/超时）两类互斥判定，
 * 确保网络层失败（如 DNS 解析失败）不会 500，而是转成友好 502。</p>
 */
class BilibiliMusicApiServiceRiskHandlingTest {

    @Test
    void riskControlChallengeDetectsHttp412And403() {
        assertTrue(BilibiliMusicApiService.isRiskControlChallenge(httpStatus(412, "Precondition Failed")),
                "HTTP 412 挑战页应判定为风控挑战");
        assertTrue(BilibiliMusicApiService.isRiskControlChallenge(httpStatus(403, "Forbidden")),
                "HTTP 403 应判定为风控挑战");
    }

    @Test
    void riskControlChallengeRejectsOtherHttpStatus() {
        assertFalse(BilibiliMusicApiService.isRiskControlChallenge(httpStatus(500, "Internal Server Error")),
                "HTTP 500 不应判定为风控挑战");
        assertFalse(BilibiliMusicApiService.isRiskControlChallenge(httpStatus(429, "Too Many Requests")),
                "HTTP 429 不应判定为风控挑战");
    }

    @Test
    void riskControlChallengeDetectsDecodingFailure() {
        assertTrue(BilibiliMusicApiService.isRiskControlChallenge(new DecodingException("not json")),
                "挑战页正文非 JSON 导致的解码失败应判定为风控挑战");
    }

    @Test
    void networkFailureDetectsWebClientRequestException() {
        assertTrue(BilibiliMusicApiService.isNetworkFailure(networkFailure()),
                "DNS 解析失败（UnknownHostException）包装的 WebClientRequestException 应判定为网络层失败");
    }

    @Test
    void networkFailureRejectsRiskControlExceptions() {
        assertFalse(BilibiliMusicApiService.isNetworkFailure(httpStatus(412, "Precondition Failed")),
                "HTTP 412 是风控挑战，不应判定为网络层失败");
        assertFalse(BilibiliMusicApiService.isNetworkFailure(new DecodingException("not json")),
                "解码失败是风控挑战，不应判定为网络层失败");
    }

    @Test
    void networkFailureFallbackReturnsFriendlyApiRequestException() {
        ApiRequestException ex = assertThrows(ApiRequestException.class,
                () -> BilibiliMusicApiService.<String>networkFailureFallback(networkFailure()).block(),
                "网络层失败应转成友好 ApiRequestException（502），而非漏到通用 500");
        assertTrue(ex.getMessage().contains("无法连接B站服务器"),
                "文案应提示检查网络/DNS，实际: " + ex.getMessage());
    }

    private static WebClientResponseException httpStatus(int status, String text) {
        return WebClientResponseException.create(status, text, null, null, null);
    }

    private static WebClientRequestException networkFailure() {
        return new WebClientRequestException(
                new UnknownHostException("Failed to resolve 'api.bilibili.com'"),
                HttpMethod.GET,
                URI.create("https://api.bilibili.com/x/web-interface/nav"),
                new HttpHeaders());
    }
}
