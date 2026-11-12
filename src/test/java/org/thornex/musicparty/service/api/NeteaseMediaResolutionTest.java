package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.*;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.media.MediaConsumer;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class NeteaseMediaResolutionTest {
    private AppProperties properties() {
        var properties = new AppProperties();
        properties.getNetease().setBaseUrl("http://netease.test");
        properties.getNetease().setCookie("MUSIC_U=fixture; __csrf=a+b/&=?");
        properties.getNetease().setQuality("lossless");
        return properties;
    }

    private ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(body).build();
    }

    @Test
    void resolutionUsesQualityCookieAndTimestampWithoutReadingMetadata() {
        List<URI> requests = new ArrayList<>();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            requests.add(request.url());
            return Mono.just(json("{\"data\":[{\"url\":\"http://audio.test/fresh\"}]}"));
        }).build();
        var media = new NeteaseMusicApiService(client, properties()).resolveMedia("123", MediaConsumer.BROWSER).block();
        assertEquals("https://audio.test/fresh", media.url());
        assertEquals(1, requests.size());
        assertEquals("/song/url/v1", requests.getFirst().getPath());
        assertTrue(requests.getFirst().getQuery().contains("level=lossless"));
        assertTrue(requests.getFirst().getQuery().contains("timestamp="));
        assertTrue(requests.getFirst().getQuery().contains("cookie=MUSIC_U=fixture; __csrf=a+b/&=?"));
    }

    @Test
    void primaryFailureUsesBitrateFallbackWithFreshTimestamp() {
        List<URI> requests = new ArrayList<>();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            requests.add(request.url());
            return Mono.just(request.url().getPath().endsWith("/v1")
                    ? ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).body("private-data").build()
                    : json("{\"data\":[{\"url\":\"https://audio.test/fallback\"}]}"));
        }).build();
        var media = new NeteaseMusicApiService(client, properties()).resolveMedia("123", MediaConsumer.SERVER).block();
        assertEquals("https://audio.test/fallback", media.url());
        assertEquals(2, requests.size());
        assertTrue(requests.get(1).getQuery().contains("br=999000"));
        assertTrue(requests.get(1).getQuery().contains("timestamp="));
    }

    @Test
    void publicAudioWorksWithoutAccountCookie() {
        var properties = properties();
        properties.getNetease().setCookie("");
        WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(json(
                "{\"data\":[{\"url\":\"https://audio.test/public\"}]}"))).build();
        assertEquals("https://audio.test/public", new NeteaseMusicApiService(client, properties)
                .resolveMedia("123", MediaConsumer.BROWSER).block().url());
    }

    @Test
    void emptyUrlsReturnExplicitErrorWithoutLeakingPayload() {
        for (String body : List.of("{\"data\":[{\"url\":null}]}", "{\"private\":\"fixture-secret\"}")) {
            WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(json(body))).build();
            ApiRequestException error = assertThrows(ApiRequestException.class, () -> new NeteaseMusicApiService(client, properties())
                    .resolveMedia("123", MediaConsumer.BROWSER).block());
            assertFalse(error.getMessage().contains("fixture-secret"));
        }
    }
}
