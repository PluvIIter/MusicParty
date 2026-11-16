package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;

import static org.junit.jupiter.api.Assertions.*;

class NeteaseMusicApiServiceTest {

    private AppProperties props;
    private NeteaseMusicApiService service;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.getNetease().setQuality("exhigh");
        service = new NeteaseMusicApiService(WebClient.builder().build(), props);
    }

    @Test
    void isCookieConfiguredReflectsCookieState() {
        assertFalse(service.isCookieConfigured(), "未配置应为 false");
        props.getNetease().setCookie("MUSIC_U=abc; __csrf=def");
        assertTrue(service.isCookieConfigured(), "已配置应为 true");
        props.getNetease().setCookie("  ");
        assertFalse(service.isCookieConfigured(), "空白不算配置");
    }

    @Test
    void resolveBrMapsLevelToBitrate() {
        assertEquals(320_000, service.resolveBr("exhigh"));
        assertEquals(320_000, service.resolveBr("EXHIGH"));
        assertEquals(128_000, service.resolveBr("standard"));
        assertEquals(192_000, service.resolveBr("higher"));
        assertEquals(999_000, service.resolveBr("lossless"));
        assertEquals(999_000, service.resolveBr("hires"));
        assertEquals(320_000, service.resolveBr("unknown"));
        assertEquals(320_000, service.resolveBr(null));
    }
    @Test
    void searchPagesByOffsetAndReturnsTheNextCursor() throws Exception {
        var properties = new AppProperties();
        properties.getNetease().setBaseUrl("https://netease.test");
        var requests = new java.util.ArrayList<String>();
        var songs = new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();
        for (int index = 0; index < 30; index++) {
            var song = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                    .put("id", "song" + index).put("name", "Song " + index).put("dt", 180000);
            song.putArray("artists").addObject().put("name", "Artist");
            song.putObject("al").put("picUrl", "https://p1.music.126.net/cover.jpg");
            songs.add(song);
        }
        var body = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("code", 200);
        var result = body.putObject("result").put("songCount", 120);
        result.set("songs", songs);
        var client = org.springframework.web.reactive.function.client.WebClient.builder()
                .exchangeFunction(request -> {
                    requests.add(request.url().toString());
                    return reactor.core.publisher.Mono.just(
                            org.springframework.web.reactive.function.client.ClientResponse
                                    .create(org.springframework.http.HttpStatus.OK)
                                    .header("Content-Type", "application/json")
                                    .body(body.toString()).build());
                }).build();
        var api = new NeteaseMusicApiService(client, properties);

        var page = api.searchMusic("query", 30).block();

        assertTrue(requests.get(0).contains("limit=30"));
        assertTrue(requests.get(0).contains("offset=30"));
        assertEquals(30, page.items().size());
        assertEquals(60, page.nextOffset());
        assertEquals(120L, page.total());
    }
}