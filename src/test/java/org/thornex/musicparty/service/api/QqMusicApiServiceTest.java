package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.media.MediaConsumer;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.thornex.musicparty.service.api.QqMusicTestSupport.*;

class QqMusicApiServiceTest {
    private String user() { return "q1." + Base64.getUrlEncoder().withoutPadding().encodeToString("{\"uin\":\"123\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private String query(org.springframework.web.reactive.function.client.ClientRequest request) { return request.url().getQuery(); }

    @Test void allNativeQueryRoutesUseConfiguredAccountButKeepExplicitTarget() {
        var properties = properties();
        properties.getQqmusic().setCookie("{\"musicid\":999,\"musickey\":\"accountA\"}");
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        List<String> requests = new ArrayList<>();
        var api = api(properties, client((request, ignored) -> {
            assertEquals(org.springframework.http.HttpMethod.GET, request.method());
            assertTrue(request.headers().getFirst("Cookie").contains("musicid=\"999\""));
            assertTrue(request.headers().getFirst("Cookie").contains("musickey=\"accountA\""));
            assertNull(request.headers().getFirst("Authorization"));
            requests.add(request.url().getPath());
            String body = switch (request.url().getPath()) {
                case "/search/search_by_type" -> query(request).contains("search_type=8")
                        ? "{\"user\":[{\"uin\":\"123\",\"encrypt_uin\":\"oKEA**\",\"title\":\"<em>Public</em>\",\"pic\":\"https://y.qq.com/avatar\"}]}"
                        : "{\"song\":[" + TRACK + "]}";
                case "/song/songMID/detail" -> "{\"track\":" + TRACK + "}";
                case "/song/songMID/lyric" -> "{\"lyric\":\"[00:00.00]Song\"}";
                case "/user/123/created_songlists" -> "{\"playlists\":[{\"id\":42,\"title\":\"Playlist\",\"songnum\":1,\"picurl\":\"\"}],\"finished\":true,\"total\":1}";
                case "/songlist/42/detail" -> "{\"info\":{\"id\":42,\"title\":\"Playlist\",\"songnum\":1},\"songs\":[" + TRACK + "],\"total\":1,\"hasmore\":0}";
                case "/album/albumMID/detail" -> "{\"album\":{\"mid\":\"albumMID\",\"name\":\"Album\"},\"singers\":[{\"name\":\"Artist\"}]}";
                case "/album/albumMID/songs" -> "{\"album_mid\":\"albumMID\",\"song_list\":[" + TRACK + "],\"total_num\":1}";
                default -> throw new AssertionError(request.url());
            };
            return Mono.just(data(json(body)));
        }), credentials);
        assertEquals("songMID", api.searchMusic("Song").block().getFirst().id());
        assertEquals("Public", api.searchUsers("Public").block().getFirst().name());
        assertEquals(180000, api.getMusic("songMID").block().duration());
        assertEquals("[00:00.00]Song", api.getLyric("songMID").block());
        assertEquals("42", api.publicCollections(user(), "playlists", 0, 20).block().items().getFirst().id());
        assertEquals("Playlist", api.containerDetail("playlist", "42").block().name());
        assertEquals(1, api.containerPage("playlist", "42", 0, 20).block().items().size());
        assertEquals("Artist", api.getAlbum("albumMID").block().artists().getFirst());
        assertEquals("albumMID", api.getAlbumForSong("songMID").block().id());
        assertFalse(requests.stream().anyMatch(path -> path.startsWith("/v1/")));
    }

    @Test void targetReadFailureNeverFallsBackToConfiguredAccountsOwnCollections() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        credentials.updateCookie("{\"musicid\":999,\"musickey\":\"accountA\"}");
        var requests = new ArrayList<String>();
        var api = api(properties, client((request, ignored) -> {
            requests.add(request.url().getPath());
            assertTrue(request.headers().getFirst("Cookie").contains("musicid=\"999\""));
            assertEquals("/user/123/created_songlists", request.url().getPath());
            return Mono.just(response(HttpStatus.OK, "{\"code\":-1,\"msg\":\"upstream refused\"}"));
        }), credentials);
        assertThrows(ApiRequestException.class, () -> api.publicCollections(user(), "playlists", 0, 20).block());
        assertEquals(List.of("/user/123/created_songlists"), requests);
    }

    @Test void albumChainStopsRatherThanMixingAccountsAfterCredentialUpdate() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        credentials.updateCookie("{\"musicid\":999,\"musickey\":\"accountA\"}");
        var calls = new AtomicInteger();
        var api = api(properties, client((request, ignored) -> {
            calls.incrementAndGet();
            assertEquals("/album/albumMID/detail", request.url().getPath());
            credentials.updateCookie("{\"musicid\":888,\"musickey\":\"accountC\"}");
            return Mono.just(data(json("{\"album\":{\"mid\":\"albumMID\",\"name\":\"Album\"},\"singers\":[]}")));
        }), credentials);
        assertTrue(assertThrows(ApiRequestException.class, () -> api.getAlbum("albumMID").block()).getMessage().startsWith("CREDENTIAL_CHANGED"));
        assertEquals(1, calls.get());
    }

    @Test void arbitraryOffsetsCrossNativePageBoundaryWithoutDroppingOrRepeatingSongs() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        List<Integer> pages = new ArrayList<>();
        var api = api(properties, client((request, ignored) -> {
            boolean second = query(request).contains("page=2");
            pages.add(second ? 2 : 1);
            var songs = MAPPER.createArrayNode();
            int start = second ? 100 : 0;
            for (int index = start; index < Math.min(start + 100, 150); index++) {
                var song = (com.fasterxml.jackson.databind.node.ObjectNode) json(TRACK).deepCopy();
                song.put("mid", "songMID" + index);
                songs.add(song);
            }
            var body = MAPPER.createObjectNode().put("total", 150).put("hasmore", second ? 0 : 1);
            body.set("songs", songs);
            return Mono.just(data(body));
        }), credentials);
        var first = api.containerPage("playlist", "42", 95, 20).block();
        assertEquals(List.of(1, 2), pages);
        assertEquals("songMID95", first.items().getFirst().id());
        assertEquals("songMID114", first.items().getLast().id());
        assertEquals(115, first.nextOffset());
        pages.clear();
        var last = api.containerPage("playlist", "42", 145, 20).block();
        assertEquals(5, last.items().size());
        assertNull(last.nextOffset());
    }

    @Test void createdListsDoNotInferPrivacyFromReturnedFlags() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        var api = api(properties, client((request, ignored) -> Mono.just(data(json("{\"playlists\":[{\"id\":41,\"title\":\"Private\",\"songnum\":1,\"dir_show\":2,\"invalid\":true},{\"id\":42,\"title\":\"Public\",\"songnum\":1}],\"finished\":true}")))), credentials);
        var first = api.publicCollections(user(), "playlists", 0, 1).block();
        assertEquals("41", first.items().getFirst().id());
        assertEquals(1, first.nextOffset());
        assertEquals("42", api.publicCollections(user(), "playlists", 1, 1).block().items().getFirst().id());
    }

    @Test void mediaUsesNativeFileEnumAndCookieHeaderNeverQueryAndKeepsFullTimeline() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        credentials.updateCookie("{\"musicid\":123,\"musickey\":\"secret;\\\"x\",\"str_musicid\":\"different\",\"refresh_token\":\"refresh\"}");
        var api = api(properties, client((request, ignored) -> {
            if (request.url().getPath().endsWith("/detail")) {
                assertTrue(request.headers().getFirst("Cookie").contains("musicid=\"123\""));
                assertTrue(request.headers().getFirst("Cookie").contains("musickey=\"secret\\073\\\"x\""));
                return Mono.just(data(json("{\"track\":" + TRACK.replace("\"size_128mp3\":12345", "\"size_128mp3\":12345,\"size_try\":5000") + "}")));
            }
            assertTrue(request.headers().getFirst("Cookie").contains("musicid=\"123\""));
            assertTrue(request.headers().getFirst("Cookie").contains("musickey=\"secret\\073\\\"x\""));
            assertFalse(request.url().toString().contains("secret"));
            assertTrue(query(request).contains("media_mid=mediaMID"));
            String quality = query(request).contains("file_type=7") ? "FLAC" : query(request).contains("file_type=12") ? "MP3_320" : "MP3_128";
            return Mono.just(data(media(quality, "mediaMID")));
        }), credentials);
        var standard = api.resolveMedia("songMID", MediaConsumer.BROWSER).block();
        assertEquals(".mp3", standard.extension());
        assertTrue(standard.url().startsWith("https://isure.stream.qqmusic.qq.com/M500mediaMID.mp3"));
        properties.getQqmusic().setQuality("MP3_320");
        var high = api.resolveMedia("songMID", MediaConsumer.SERVER).block();
        assertTrue(high.url().contains("M800mediaMID.mp3"));
        assertEquals(standard.timelineKey(), high.timelineKey());
        properties.getQqmusic().setQuality("FLAC");
        var flac = api.resolveMedia("songMID", MediaConsumer.SERVER).block();
        assertEquals(".flac", flac.extension());
        assertEquals(standard.timelineKey(), flac.timelineKey());
        assertNotEquals(standard.representationKey(), flac.representationKey());
        assertEquals(".mp3", api.resolveFallbackMedia("songMID").block().extension());
    }

    @Test void inaccessiblePrimaryDoesNotBlockIndependentStandardFallback() {
        var properties = properties();
        properties.getQqmusic().setQuality("FLAC");
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        var api = api(properties, client((request, ignored) -> {
            if (request.url().getPath().endsWith("/detail")) return Mono.just(data(json("{\"track\":" + TRACK.replace("\"size_flac\":34567", "\"size_flac\":0") + "}")));
            assertTrue(query(request).contains("file_type=13"));
            return Mono.just(data(media("MP3_128", "mediaMID")));
        }), credentials);
        assertThrows(ApiRequestException.class, () -> api.resolveMedia("songMID", MediaConsumer.SERVER).block());
        assertEquals(".mp3", api.resolveFallbackMedia("songMID").block().extension());
    }

    @Test void encryptedWrongFileOrExpiredAuthorizationRemainsRejected() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        for (String replacement : List.of("encrypted", "wrongfile", "expired", "denied", "empty")) {
            var api = api(properties, client((request, ignored) -> {
                if (request.url().getPath().endsWith("/detail")) return Mono.just(data(json("{\"track\":" + TRACK + "}")));
                var authorization = (com.fasterxml.jackson.databind.node.ObjectNode) media("MP3_128", "mediaMID");
                var file = (com.fasterxml.jackson.databind.node.ObjectNode) authorization.path("data").get(0);
                switch (replacement) {
                    case "encrypted" -> file.put("ekey", "secret");
                    case "wrongfile" -> file.put("purl", "RS02mediaMID.mgg?vkey=x");
                    case "expired" -> authorization.put("expiration", 1);
                    case "denied" -> file.put("result", 104003);
                    case "empty" -> file.put("purl", "");
                }
                return Mono.just(data(authorization));
            }), credentials);
            assertThrows(ApiRequestException.class, () -> api.resolveMedia("songMID", MediaConsumer.SERVER).block());
        }
    }

    @Test void pendingMetadataAndLateResponsesRespectCredentialRevocation() {
        for (boolean update : List.of(false, true)) {
            var properties = properties();
            var credentials = new QqMusicCredentialService(properties, MAPPER);
            credentials.updateCookie("{\"musicid\":123,\"musickey\":\"old\"}");
            Sinks.One<org.springframework.web.reactive.function.client.ClientResponse> pending = Sinks.one();
            AtomicInteger privateCalls = new AtomicInteger();
            var api = api(properties, client((request, ignored) -> {
                if (request.url().getPath().endsWith("/detail")) return pending.asMono();
                privateCalls.incrementAndGet();
                return Mono.just(data(media("MP3_128", "mediaMID")));
            }), credentials);
            var result = api.resolveMedia("songMID", MediaConsumer.SERVER).toFuture();
            credentials.updateCookie(update ? "{\"musicid\":123,\"musickey\":\"new\"}" : "");
            pending.tryEmitValue(data(json("{\"track\":" + TRACK + "}")));
            assertTrue(result.isCompletedExceptionally());
            assertEquals(0, privateCalls.get());
        }
    }

    @Test void albumPaginationCollectsEveryNativeSongAndRejectsIncompleteCollections() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        List<Integer> pages = new ArrayList<>();
        var api = api(properties, client((request, ignored) -> {
            if (request.url().getPath().endsWith("/detail")) return Mono.just(data(json("{\"album\":{\"mid\":\"albumMID\",\"name\":\"Album\"},\"singers\":[]}")));
            int page = query(request).contains("page=2") ? 2 : 1;
            pages.add(page);
            var songs = MAPPER.createArrayNode();
            for (int index = (page - 1) * 100; index < Math.min(page * 100, 101); index++) {
                var song = (com.fasterxml.jackson.databind.node.ObjectNode) json(TRACK).deepCopy();
                song.put("mid", "songMID" + index);
                songs.add(song);
            }
            var body = MAPPER.createObjectNode().put("album_mid", "albumMID").put("total_num", 101);
            body.set("song_list", songs);
            return Mono.just(data(body));
        }), credentials);
        var album = api.getAlbum("albumMID").block();
        assertEquals(101, album.trackCount());
        assertEquals("songMID100", album.songs().getLast().id());
        assertEquals(List.of(1, 2), pages);
        for (String value : List.of("{\"playlists\":[],\"finished\":false,\"total\":0}", "{\"playlists\":[],\"finished\":true,\"total\":20}")) {
            var truncated = api(properties, client((request, ignored) -> Mono.just(data(json(value)))), credentials);
            assertTrue(assertThrows(ApiRequestException.class, () -> truncated.publicCollections(user(), "playlists", 0, 20).block()).getMessage().startsWith("COLLECTION_INCOMPLETE"));
        }
    }

    @Test void lateAuthorizedResponseAfterCredentialUpdateCannotBeCommitted() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        credentials.updateCookie("{\"musicid\":123,\"musickey\":\"old\"}");
        Sinks.One<org.springframework.web.reactive.function.client.ClientResponse> authorization = Sinks.one();
        var api = api(properties, client((request, ignored) -> request.url().getPath().endsWith("/detail")
                ? Mono.just(data(json("{\"track\":" + TRACK + "}"))) : authorization.asMono()), credentials);
        var result = api.resolveMedia("songMID", MediaConsumer.SERVER).toFuture();
        credentials.updateCookie("{\"musicid\":123,\"musickey\":\"new\"}");
        authorization.tryEmitValue(data(media("MP3_128", "mediaMID")));
        assertTrue(result.isCompletedExceptionally());
    }

    @Test void nativeErrorEnvelopeIsSanitizedAndInvalidConfiguredAccountIsNotAnonymous() {
        var properties = properties();
        properties.getQqmusic().setCookie("invalid-secret");
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        var api = api(properties, client((request, ignored) -> Mono.just(response(HttpStatus.OK, "{\"code\":-1,\"msg\":\"private-secret\"}"))), credentials);
        assertFalse(api.isCredentialConfigured());
        assertFalse(assertThrows(ApiRequestException.class, () -> api.searchMusic("song").block()).getMessage().contains("private-secret"));
        assertTrue(assertThrows(ApiRequestException.class, () -> api.resolveMedia("songMID", MediaConsumer.SERVER).block()).getMessage().startsWith("INVALID_CREDENTIAL"));
    }
}
