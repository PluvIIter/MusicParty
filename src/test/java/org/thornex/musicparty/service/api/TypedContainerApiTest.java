package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.exception.ApiRequestException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TypedContainerApiTest {
    private ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(body).build();
    }

    private AppProperties properties() {
        var properties = new AppProperties();
        properties.getNetease().setBaseUrl("https://netease.test");
        properties.getBilibili().setBaseUrl("https://bilibili.test");
        return properties;
    }

    @Test
    void neteaseTypedContainersReadPublicMetadataAndKeepTrackOffsets() {
        List<URI> requests = new ArrayList<>();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            requests.add(request.url());
            assertFalse(request.url().getQuery().contains("cookie="));
            return Mono.just(json(request.url().getPath().endsWith("detail")
                    ? "{\"code\":200,\"playlist\":{\"id\":42,\"name\":\"Public\",\"trackCount\":2}}"
                    : "{\"code\":200,\"songs\":[{\"id\":1,\"name\":\"Track\",\"ar\":[],\"dt\":1000}]}"));
        }).build();
        var adapter = new NeteaseMusicApiService(client, properties());
        assertEquals("Public", adapter.containerDetail("playlist", "42").block().name());
        var page = adapter.containerPage("playlist", "42", 10, 1).block();
        assertEquals("1", page.items().getFirst().id());
        assertEquals(11, page.nextOffset());
        assertTrue(requests.getLast().getQuery().contains("offset=10"));
        assertThrows(ApiRequestException.class, () -> adapter.containerDetail("favorites", "42").block());
        assertThrows(ApiRequestException.class, () -> adapter.containerPage("favorites", "42", 0, 1).block());
        assertEquals(2, requests.size());
    }

    @Test
    void bilibiliFilteredPageUsesRawCountToAdvanceWithoutInventingTracks() {
        var cookie = mock(BilibiliCookieService.class);
        when(cookie.getPublicCookieHeader()).thenReturn(Mono.just("buvid3=device"));
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            assertEquals("buvid3=device", request.headers().getFirst("Cookie"));
            assertTrue(request.url().getQuery().contains("media_id=42"));
            assertTrue(request.url().getQuery().contains("ps=2"));
            assertTrue(request.url().getQuery().contains("pn=3"));
            return Mono.just(json("{\"code\":0,\"data\":{\"has_more\":true,\"medias\":[{\"title\":\"已失效视频\"},{\"title\":\"已失效视频\"}]}}"));
        }).build();
        var adapter = new BilibiliMusicApiService(client, properties(), mock(BilibiliWbiService.class), cookie);
        var page = adapter.containerPage("favorites", "42", 4, 2).block();
        assertTrue(page.items().isEmpty());
        assertEquals(6, page.nextOffset());
        verify(cookie, never()).getCookieHeader();
    }

    @Test
    void bilibiliFinalPagesKeepItemsAndReturnNullCursor() {
        for (String media : List.of("{\"bvid\":\"BV-last\",\"title\":\"Last\",\"duration\":1}",
                "{\"title\":\"已失效视频\"}")) {
            var cookie = mock(BilibiliCookieService.class);
            when(cookie.getPublicCookieHeader()).thenReturn(Mono.just("buvid3=device"));
            WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(json(
                    "{\"code\":0,\"data\":{\"has_more\":false,\"medias\":[" + media + "]}}"))).build();
            var adapter = new BilibiliMusicApiService(client, properties(), mock(BilibiliWbiService.class), cookie);
            for (int offset : List.of(0, 20)) {
                var page = adapter.containerPage("favorites", "42", offset, 20).block();
                assertNull(page.nextOffset());
                if (media.contains("BV-last")) assertEquals("BV-last", page.items().getFirst().id());
                else assertTrue(page.items().isEmpty());
            }
            verify(cookie, never()).getCookieHeader();
        }
    }

    @Test
    void bilibiliEmptyPagesReturnNullCursorWithoutUnboxing() {
        for (String data : List.of("{\"has_more\":false,\"medias\":null}",
                "{\"has_more\":false,\"medias\":[]}", "{}")) {
            var cookie = mock(BilibiliCookieService.class);
            when(cookie.getPublicCookieHeader()).thenReturn(Mono.just("buvid3=device"));
            WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(json(
                    "{\"code\":0,\"data\":" + data + "}"))).build();
            var adapter = new BilibiliMusicApiService(client, properties(), mock(BilibiliWbiService.class), cookie);
            var page = adapter.containerPage("favorites", "42", 0, 20).block();
            assertTrue(page.items().isEmpty());
            assertNull(page.nextOffset());
        }
    }

    @Test
    void bilibiliShortPagesAdvanceByPageSizeEvenWhenAllItemsAreFiltered() {
        List<URI> requests = new ArrayList<>();
        var cookie = mock(BilibiliCookieService.class);
        when(cookie.getPublicCookieHeader()).thenReturn(Mono.just("buvid3=device"));
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            requests.add(request.url());
            return Mono.just(json(requests.size() == 1
                    ? "{\"code\":0,\"data\":{\"has_more\":true,\"medias\":[{\"title\":\"已失效视频\"}]}}"
                    : "{\"code\":0,\"data\":{\"has_more\":false,\"medias\":[{\"bvid\":\"BV-last\",\"title\":\"Last\"}]}}"));
        }).build();
        var adapter = new BilibiliMusicApiService(client, properties(), mock(BilibiliWbiService.class), cookie);
        var first = adapter.containerPage("favorites", "42", 0, 20).block();
        assertTrue(first.items().isEmpty());
        assertEquals(20, first.nextOffset());
        var last = adapter.containerPage("favorites", "42", first.nextOffset(), 20).block();
        assertEquals("BV-last", last.items().getFirst().id());
        assertNull(last.nextOffset());
        assertTrue(requests.getFirst().getQuery().contains("pn=1"));
        assertTrue(requests.getLast().getQuery().contains("pn=2"));
        requests.clear();
        var resources = new org.thornex.musicparty.resource.ResourceService(
                new org.thornex.musicparty.source.SourceRegistry(List.of(adapter), properties()),
                new org.thornex.musicparty.resource.ResourceCursor());
        var imported = resources.importTracks("bilibili", "favorites", "42", 100).block();
        assertEquals(List.of("BV-last"), imported.stream().map(org.thornex.musicparty.dto.Music::id).toList());
        assertEquals(2, requests.size());
    }

    @Test
    void bilibiliPublicCollectionPagingAndTypedDetailNeverUseMediaCredentials() {
        var cookie = mock(BilibiliCookieService.class);
        when(cookie.getPublicCookieHeader()).thenReturn(Mono.just("buvid3=device"));
        WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(json(
                request.url().getPath().endsWith("list-all")
                        ? "{\"code\":0,\"data\":{\"list\":[{\"id\":1,\"title\":\"One\",\"media_count\":1},{\"id\":2,\"title\":\"Two\",\"media_count\":2}]}}"
                        : "{\"code\":0,\"data\":{\"info\":{\"id\":2,\"title\":\"Two\",\"media_count\":2}}}"))).build();
        var adapter = new BilibiliMusicApiService(client, properties(), mock(BilibiliWbiService.class), cookie);
        var page = adapter.publicCollections("user", "favorites", 1, 1).block();
        assertEquals("2", page.items().getFirst().id());
        assertEquals(2L, page.total());
        assertNull(page.nextOffset());
        assertEquals("Two", adapter.containerDetail("favorites", "2").block().name());
        assertThrows(ApiRequestException.class, () -> adapter.containerDetail("playlist", "2").block());
        verify(cookie, never()).getCookieHeader();
    }

    @Test
    void privateContainersAndInvalidPagePositionsRemainErrors() {
        var cookie = mock(BilibiliCookieService.class);
        when(cookie.getPublicCookieHeader()).thenReturn(Mono.just("buvid3=device"));
        WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(json("{\"code\":-404}"))).build();
        var adapter = new BilibiliMusicApiService(client, properties(), mock(BilibiliWbiService.class), cookie);
        assertThrows(ApiRequestException.class, () -> adapter.containerDetail("favorites", "42").block());
        assertThrows(ApiRequestException.class, () -> adapter.containerPage("favorites", "42", 0, 20).block());
        assertThrows(ApiRequestException.class, () -> adapter.containerPage("favorites", "42", 3, 20).block());
        verify(cookie, times(2)).getPublicCookieHeader();
    }
}
