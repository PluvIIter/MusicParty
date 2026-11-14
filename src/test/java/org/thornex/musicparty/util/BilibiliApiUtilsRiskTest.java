package org.thornex.musicparty.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.UnsupportedMediaTypeException;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.thornex.musicparty.exception.ApiRequestException;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BilibiliApiUtilsRiskTest {
    private static final String SUCCESS_JSON = "{\"code\":0,\"data\":{\"cid\":\"123\",\"duration\":10,\"title\":\"t\",\"owner\":{\"name\":\"a\"},\"pic\":\"http://i0.hdslb.com/x.jpg\"}}";
    private static final String CHALLENGE_HTML = "<!DOCTYPE html><html><body>验证后继续访问</body></html>";

    private static ClientResponse response(int status, String body, String contentType) {
        return ClientResponse.create(HttpStatus.valueOf(status))
                .header("Content-Type", contentType).body(body).build();
    }

    private static WebClient client(int status, String body, String contentType, AtomicInteger calls) {
        return WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            return Mono.just(response(status, body, contentType));
        }).build();
    }

    @ParameterizedTest
    @ValueSource(ints = {412, 403})
    void viewRiskControlRetriesOnceThenReturnsFriendlyError(int status) {
        AtomicInteger calls = new AtomicInteger();
        var error = assertThrows(ApiRequestException.class, () -> BilibiliApiUtils
                .getVideoInfo("BV1", client(status, CHALLENGE_HTML, "text/html", calls), "http://bili.test", "")
                .block());
        assertEquals("B 站风控拦截，请稍后重试", error.getMessage());
        assertEquals(2, calls.get());
    }

    @Test
    void htmlDecodingIsWrappedAsSuccessfulResponseException() {
        AtomicInteger calls = new AtomicInteger();
        var error = assertThrows(WebClientResponseException.class, () -> client(200, CHALLENGE_HTML, "text/html", calls)
                .get().uri("http://bili.test").retrieve().bodyToMono(JsonNode.class).block());
        assertEquals(200, error.getStatusCode().value());
        assertInstanceOf(UnsupportedMediaTypeException.class, error.getCause());
        assertTrue(BilibiliApiUtils.isRiskControl(error));
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "text/html;charset=UTF-8"})
    void viewSuccessfulHtmlChallengeRetriesOnceThenReturnsFriendlyError(String contentType) {
        AtomicInteger calls = new AtomicInteger();
        var error = assertThrows(ApiRequestException.class, () -> BilibiliApiUtils
                .getVideoInfo("BV1", client(200, CHALLENGE_HTML, contentType, calls), "http://bili.test", "")
                .block());
        assertEquals("B 站风控拦截，请稍后重试", error.getMessage());
        assertEquals(2, calls.get());
    }

    @Test
    void viewMalformedJsonRetriesOnceThenReturnsFriendlyError() {
        AtomicInteger calls = new AtomicInteger();
        var error = assertThrows(ApiRequestException.class, () -> BilibiliApiUtils
                .getVideoInfo("BV1", client(200, "{\"code\":", "application/json", calls), "http://bili.test", "")
                .block());
        assertEquals("B 站风控拦截，请稍后重试", error.getMessage());
        assertEquals(2, calls.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 404, 500})
    void viewOrdinaryHttpErrorsAreNotRetriedOrReportedAsRiskControl(int status) {
        AtomicInteger calls = new AtomicInteger();
        var error = assertThrows(WebClientResponseException.class, () -> BilibiliApiUtils
                .getVideoInfo("BV1", client(status, CHALLENGE_HTML, "text/html", calls), "http://bili.test", "")
                .block());
        assertEquals(status, error.getStatusCode().value());
        assertFalse(BilibiliApiUtils.isRiskControl(error));
        assertEquals(1, calls.get());
    }

    @Test
    void viewPlainTextErrorIsNotRetriedOrReportedAsRiskControl() {
        AtomicInteger calls = new AtomicInteger();
        var error = assertThrows(WebClientResponseException.class, () -> BilibiliApiUtils
                .getVideoInfo("BV1", client(200, "upstream unavailable", "text/plain", calls), "http://bili.test", "")
                .block());
        assertEquals(200, error.getStatusCode().value());
        assertInstanceOf(UnsupportedMediaTypeException.class, error.getCause());
        assertFalse(BilibiliApiUtils.isRiskControl(error));
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {-400, -404, -412})
    void viewJsonBusinessErrorsAreNotRetriedOrReportedAsRiskControl(int code) {
        AtomicInteger calls = new AtomicInteger();
        String body = "{\"code\":" + code + ",\"message\":\"business error\"}";
        var error = assertThrows(ApiRequestException.class, () -> BilibiliApiUtils
                .getVideoInfo("BV1", client(200, body, "application/json", calls), "http://bili.test", "")
                .block());
        assertEquals("Could not get Bilibili video info: business error", error.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void viewHtmlChallengeCanRecoverOnRetry() {
        AtomicInteger calls = new AtomicInteger();
        WebClient webClient = WebClient.builder().exchangeFunction(request -> Mono.just(
                calls.incrementAndGet() == 1
                        ? response(200, CHALLENGE_HTML, "text/html")
                        : response(200, SUCCESS_JSON, "application/json"))).build();
        var info = BilibiliApiUtils.getVideoInfo("BV1", webClient, "http://bili.test", "").block();
        assertNotNull(info);
        assertEquals("123", info.cid());
        assertEquals(2, calls.get());
    }

    @Test
    void viewSuccessIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        var info = BilibiliApiUtils
                .getVideoInfo("BV1", client(200, SUCCESS_JSON, "application/json", calls), "http://bili.test", "")
                .block();
        assertNotNull(info);
        assertEquals("123", info.cid());
        assertEquals(1, calls.get());
    }
}
