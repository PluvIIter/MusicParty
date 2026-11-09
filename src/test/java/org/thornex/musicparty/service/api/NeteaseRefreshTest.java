package org.thornex.musicparty.service.api;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.*;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.exception.ApiRequestException;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NeteaseRefreshTest {
    private AppProperties props() {
        AppProperties p = new AppProperties(); p.getNetease().setBaseUrl("http://netease.test");
        p.getNetease().setCookie("MUSIC_U=fixture; __csrf=a+b/&=?"); p.getNetease().setQuality("lossless"); return p;
    }
    private ClientResponse json(String body) { return ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(body).build(); }
    @Test void refreshReusesQualityCookieAndAddsCacheBusterWithoutReadingMetadata() {
        List<URI> requests = new ArrayList<>();
        WebClient client = WebClient.builder().exchangeFunction(r -> { requests.add(r.url()); return Mono.just(json("{\"data\":[{\"url\":\"http://audio.test/fresh\"}]}")); }).build();
        assertEquals("https://audio.test/fresh", new NeteaseMusicApiService(client, props()).refreshSongUrl("123").block());
        assertEquals(1, requests.size()); assertEquals("/song/url/v1", requests.getFirst().getPath());
        assertTrue(requests.getFirst().getQuery().contains("level=lossless"));
        assertTrue(requests.getFirst().getQuery().contains("timestamp="));
        assertTrue(requests.getFirst().getQuery().contains("cookie=MUSIC_U=fixture; __csrf=a+b/&=?"));
    }
    @Test void primaryFailureUsesExistingBitrateFallbackWithFreshTimestamp() {
        List<URI> requests = new ArrayList<>();
        WebClient client = WebClient.builder().exchangeFunction(r -> {
            requests.add(r.url()); return Mono.just(r.url().getPath().endsWith("/v1")
                ? ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).body("private-data").build()
                : json("{\"data\":[{\"url\":\"https://audio.test/fallback\"}]}"));
        }).build();
        assertEquals("https://audio.test/fallback", new NeteaseMusicApiService(client, props()).refreshSongUrl("123").block());
        assertEquals(2, requests.size()); assertTrue(requests.get(1).getQuery().contains("br=999000"));
        assertTrue(requests.get(1).getQuery().contains("timestamp="));
    }
    @Test void invalidIdOrMissingCookieNeverContactsUpstream() {
        WebClient client = WebClient.builder().exchangeFunction(r -> { fail("Must not contact API"); return Mono.empty(); }).build();
        assertThrows(ApiRequestException.class, () -> new NeteaseMusicApiService(client, props()).refreshSongUrl("invalid").block());
        AppProperties blank = props(); blank.getNetease().setCookie("");
        assertThrows(ApiRequestException.class, () -> new NeteaseMusicApiService(client, blank).refreshSongUrl("123").block());
    }
    @Test void bothFailuresAndEmptyUrlsReturnGenericError() {
        for (String body : List.of("{\"data\":[{\"url\":null}]}", "{\"private\":\"fixture-secret\"}")) {
            WebClient client = WebClient.builder().exchangeFunction(r -> Mono.just(json(body))).build();
            ApiRequestException e = assertThrows(ApiRequestException.class, () -> new NeteaseMusicApiService(client, props()).refreshSongUrl("123").block());
            assertFalse(e.getMessage().contains("fixture-secret"));
        }
    }
}
