package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.media.MediaConsumer;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdapterMediaContractTest {
    private ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(body).build();
    }

    @Test void neteaseMetadataDoesNotRequireOrSendAccountCookie() {
        AppProperties props = new AppProperties(); props.getNetease().setBaseUrl("http://sidecar.test"); props.getNetease().setCookie("MUSIC_U=secret");
        List<String> routes = new ArrayList<>();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            routes.add(request.url().getPath()); assertFalse(request.url().getQuery().contains("cookie="));
            return Mono.just(json("{\"songs\":[{\"id\":1,\"name\":\"Track\",\"ar\":[],\"dt\":1000}]}"));
        }).build();
        var music = new NeteaseMusicApiService(client, props).getMusic("1").block();
        assertEquals(List.of("/song/detail"), routes); assertEquals("https://music.163.com/song?id=1", music.externalUrl());
    }

    @Test void neteaseLosslessMediaCarriesActualFormatAndCredentialsOnlyOnResolve() {
        AppProperties props = new AppProperties(); props.getNetease().setBaseUrl("http://sidecar.test"); props.getNetease().setCookie("MUSIC_U=secret");
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            assertTrue(request.url().getQuery().contains("cookie=MUSIC_U=secret"));
            return Mono.just(json("{\"data\":[{\"url\":\"https://cdn.test/song.flac\",\"type\":\"flac\",\"br\":999000,\"time\":1000,\"freeTrialInfo\":null}]}"));
        }).build();
        var media = new NeteaseMusicApiService(client, props).resolveMedia("1", MediaConsumer.SERVER).block();
        assertEquals(".flac", media.extension()); assertEquals("audio/flac", media.mimeType());
        assertFalse(media.headers().containsKey("Cookie")); assertTrue(media.representationKey().contains("flac"));
    }

    @Test void bilibiliMetadataUsesNoAccountCookieAndNeverStartsDownload() {
        AppProperties props = new AppProperties(); props.getBilibili().setBaseUrl("http://bili.test");
        var cookie = mock(BilibiliCookieService.class); var wbi = mock(BilibiliWbiService.class);
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            assertEquals("", request.headers().getFirst("Cookie"));
            return Mono.just(json("{\"code\":0,\"data\":{\"cid\":10,\"title\":\"Track\",\"duration\":1,\"owner\":{\"name\":\"Artist\"}}}"));
        }).build();
        var music = new BilibiliMusicApiService(client, props, wbi, cookie).getMusic("BV1abc").block();
        assertEquals("BV1abc", music.id()); assertEquals("https://www.bilibili.com/video/BV1abc", music.externalUrl());
        verifyNoInteractions(cookie, wbi);
    }

    @Test void neteaseTimelineIgnoresCodecButSeparatesTrialWindows() {
        AppProperties props = new AppProperties(); props.getNetease().setBaseUrl("http://sidecar.test");
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            int call = calls.getAndIncrement();
            String type = call == 0 ? "mp3" : "flac";
            String trial = call < 2 ? "null" : "{\"start\":100,\"end\":900}";
            return Mono.just(json("{\"data\":[{\"url\":\"https://cdn.test/song\",\"type\":\"" + type
                    + "\",\"time\":1000,\"freeTrialInfo\":" + trial + "}]}"));
        }).build();
        var adapter = new NeteaseMusicApiService(client, props);
        var browser = adapter.resolveMedia("1", MediaConsumer.BROWSER).block();
        var server = adapter.resolveMedia("1", MediaConsumer.SERVER).block();
        var trial = adapter.resolveMedia("1", MediaConsumer.SERVER).block();
        assertNotEquals(browser.representationKey(), server.representationKey());
        assertEquals(browser.timelineKey(), server.timelineKey());
        assertNotEquals(server.timelineKey(), trial.timelineKey());
    }

    @Test void bilibiliHtml5AndDashShareTimelineOnlyForSameFullVideo() {
        AppProperties props = new AppProperties(); props.getBilibili().setBaseUrl("http://bili.test");
        var cookie = mock(BilibiliCookieService.class);
        when(cookie.getCookieHeader()).thenReturn(Mono.just("SESSDATA=media"));
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            if (request.url().getPath().contains("view")) {
                return Mono.just(json("{\"code\":0,\"data\":{\"cid\":10}}"));
            }
            if (request.url().getQuery().contains("fnval=1&")) {
                return Mono.just(json("{\"code\":0,\"data\":{\"timelength\":1000,\"is_preview\":0,\"durl\":[{\"url\":\"https://cdn.test/video\"}]}}"));
            }
            return Mono.just(json("{\"code\":0,\"data\":{\"timelength\":1000,\"is_preview\":0,\"dash\":{\"audio\":[{\"id\":30280,\"baseUrl\":\"https://cdn.test/audio\"}]}}}"));
        }).build();
        var adapter = new BilibiliMusicApiService(client, props, mock(BilibiliWbiService.class), cookie);
        var browser = adapter.resolveMedia("BV1abc", MediaConsumer.BROWSER).block();
        var server = adapter.resolveMedia("BV1abc", MediaConsumer.SERVER).block();
        assertEquals("html5", browser.representationKey());
        assertEquals("dash", server.representationKey());
        assertEquals(browser.timelineKey(), server.timelineKey());
        assertTrue(server.timelineKey().contains("cid:10"));
    }

    @Test void publicBilibiliCookieNeverContainsSessionSecrets() {
        AppProperties props = new AppProperties(); props.getBilibili().setCookie("SESSDATA=secret; bili_jct=secret; buvid3=device; buvid4=device4");
        var service = new BilibiliCookieService(WebClient.builder().build(), props);
        assertEquals("buvid3=device; buvid4=device4", service.getPublicCookieHeader().block());
    }
}
