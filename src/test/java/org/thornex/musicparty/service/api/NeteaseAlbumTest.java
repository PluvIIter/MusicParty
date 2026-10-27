package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.AlbumInfo;
import org.thornex.musicparty.exception.ApiRequestException;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NeteaseAlbumTest {
    private static final String ALBUM = """
            {"album":{"id":900,"name":"Test album","artists":[{"name":"Artist"}],"picUrl":"http://example.test/cover"},
             "songs":[
               {"id":2,"name":"Second","ar":[{"name":"Artist"}],"dt":120000,"al":{"picUrl":"http://example.test/2"}},
               {"id":1,"name":"First","artists":[{"name":"Artist"}],"duration":180000},
               {"id":2,"name":"Duplicate","ar":[],"dt":1}]}
            """;

    private AppProperties properties() {
        AppProperties properties = new AppProperties();
        properties.getNetease().setBaseUrl("http://netease.test");
        properties.getNetease().setCookie("fixture-cookie");
        return properties;
    }

    private ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(body).build();
    }

    @Test
    void resolvesSongAndPreservesAlbumOrderAndMetadata() {
        List<String> paths = new ArrayList<>();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            paths.add(request.url().getPath());
            assertTrue(request.url().getQuery().contains("cookie=fixture-cookie"));
            return Mono.just(json(request.url().getPath().equals("/song/detail")
                    ? "{\"songs\":[{\"al\":{\"id\":900}}]}" : ALBUM));
        }).build();
        AlbumInfo album = new NeteaseMusicApiService(client, properties()).getAlbumForSong("101").block();
        assertNotNull(album);
        assertEquals(List.of("/song/detail", "/album"), paths);
        assertEquals("900", album.id());
        assertEquals("Test album", album.name());
        assertEquals(List.of("Artist"), album.artists());
        assertEquals("https://example.test/cover", album.coverUrl());
        assertEquals(2, album.trackCount());
        assertEquals(List.of("2", "1"), album.songs().stream().map(song -> song.id()).toList());
        assertEquals(180000, album.songs().get(1).duration());
    }

    @Test
    void supportsLegacySongAlbumField() {
        WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(json(
                request.url().getPath().equals("/song/detail")
                        ? "{\"songs\":[{\"album\":{\"id\":900}}]}" : ALBUM))).build();
        assertEquals("900", new NeteaseMusicApiService(client, properties()).getAlbumForSong("101").block().id());
    }

    @Test
    void missingAlbumAndEmptyAlbumReturnReadableErrors() {
        WebClient missing = WebClient.builder().exchangeFunction(request -> Mono.just(json("{\"songs\":[]}"))).build();
        assertThrows(ApiRequestException.class, () -> new NeteaseMusicApiService(missing, properties()).getAlbumForSong("101").block());
        WebClient empty = WebClient.builder().exchangeFunction(request -> Mono.just(json("{\"album\":{\"id\":900},\"songs\":[]}"))).build();
        assertThrows(ApiRequestException.class, () -> new NeteaseMusicApiService(empty, properties()).getAlbum("900").block());
    }

    @Test
    void missingCookieAndInvalidIdDoNotContactUpstream() {
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            fail("Invalid requests must not reach the music API");
            return Mono.empty();
        }).build();
        AppProperties properties = properties();
        assertThrows(ApiRequestException.class, () -> new NeteaseMusicApiService(client, properties).getAlbum("invalid").block());
        properties.getNetease().setCookie("");
        assertThrows(ApiRequestException.class, () -> new NeteaseMusicApiService(client, properties).getAlbum("900").block());
    }

    @Test
    void upstreamFailureDoesNotExposeResponseBody() {
        WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).body("private-upstream-data").build())).build();
        ApiRequestException error = assertThrows(ApiRequestException.class,
                () -> new NeteaseMusicApiService(client, properties()).getAlbum("900").block());
        assertFalse(error.getMessage().contains("private-upstream-data"));
    }
}
