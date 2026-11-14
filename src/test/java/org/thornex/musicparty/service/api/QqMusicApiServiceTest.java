package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.media.MediaConsumer;
import org.thornex.musicparty.source.DeliveryMode;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.thornex.musicparty.service.api.QqMusicTestSupport.*;

class QqMusicApiServiceTest {
    private static String reference(String uin, String euin) {
        StringBuilder json = new StringBuilder("{");
        if (uin != null) json.append("\"uin\":\"").append(uin).append('"');
        if (euin != null) {
            if (json.length() > 1) json.append(',');
            json.append("\"euin\":\"").append(euin).append('"');
        }
        json.append('}');
        return "q1." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String user() {
        return reference("123", "oKEA");
    }

    private static String query(ClientRequest request) {
        return request.url().getQuery();
    }

    @Test void descriptorMatchesNeteaseDefaultsApartFromPrivateFm() {
        var api = api(properties(), client((request, ignored) -> Mono.empty()), credentials());
        var descriptor = api.descriptor();
        assertEquals("QQ音乐", descriptor.label());
        assertEquals("QQ音乐", descriptor.tabLabel());
        assertEquals(DeliveryMode.DIRECT_URL, api.defaultDeliveryMode());
        assertTrue(descriptor.supportedModes().contains(DeliveryMode.DIRECT_URL));
        var capabilities = descriptor.capabilities();
        assertTrue(capabilities.search());
        assertTrue(capabilities.lyrics());
        assertTrue(capabilities.album());
        assertTrue(capabilities.userSearch());
        assertTrue(capabilities.personalCollections());
        assertTrue(capabilities.download());
        assertTrue(capabilities.radioInput());
        assertFalse(capabilities.recommendations(), "QQ 音乐不再声明私人 FM/DJ");
        assertEquals(List.of("playlists"), descriptor.collectionCategories().stream()
                .map(org.thornex.musicparty.source.CollectionCategory::id).toList());
    }

    private static QqMusicCredentialService credentials() {
        return new QqMusicCredentialService(new AppProperties());
    }

    @Test void searchCollectsNativePagesInsteadOfOnlyTheFirstPage() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties);
        List<String> pages = new ArrayList<>();
        var api = api(properties, client((request, ignored) -> {
            String queryString = query(request);
            assertEquals("/search/search_by_type", request.url().getPath());
            assertTrue(queryString.contains("num=30"));
            int page = queryString.contains("page=2") ? 2 : queryString.contains("page=3") ? 3 : 1;
            pages.add("page=" + page);
            var songs = MAPPER.createArrayNode();
            for (int index = (page - 1) * 30; index < page * 30; index++) {
                var song = (com.fasterxml.jackson.databind.node.ObjectNode) json(TRACK).deepCopy();
                song.put("mid", "songMID" + index);
                songs.add(song);
            }
            var body = MAPPER.createObjectNode().put("nextpage", page < 3 ? page + 1 : -1);
            body.set("song", songs);
            return Mono.just(data(body));
        }), credentials);
        var result = api.searchMusic("Song").block();
        assertEquals(90, result.size());
        assertEquals(List.of("page=1", "page=2", "page=3"), pages);
        assertEquals("songMID89", result.getLast().id());
    }

    @Test void searchStopsWhenUpstreamReportsNoNextPage() {
        var properties = properties();
        var calls = new ArrayList<String>();
        var api = api(properties, client((request, ignored) -> {
            calls.add(request.url().getQuery());
            return Mono.just(data(json("{\"nextpage\":-1,\"song\":[" + TRACK + "]}")));
        }), new QqMusicCredentialService(properties));
        assertEquals(1, api.searchMusic("Song").block().size());
        assertEquals(1, calls.size());
    }

    @Test void lyricsMergeTranslationOnTheOriginalTimeline() {
        var properties = properties();
        var api = api(properties, client((request, ignored) -> {
            assertEquals("/song/songMID/lyric", request.url().getPath());
            assertTrue(query(request).contains("trans=true"));
            return Mono.just(data(json("{\"lyric\":\"[00:01.00]Hello\\n[00:02.50]World\","
                    + "\"trans\":\"[00:01.00]你好\\n[00:02.50]世界\"}")));
        }), new QqMusicCredentialService(properties));
        assertEquals("[00:01.00]Hello ／ 你好\n[00:02.50]World ／ 世界", api.getLyric("songMID").block());
    }

    @Test void lyricsWithoutTranslationStayUnchanged() {
        var properties = properties();
        var api = api(properties,
                client((request, ignored) -> Mono.just(data(json("{\"lyric\":\"[00:01.00]Hello\"}")))),
                new QqMusicCredentialService(properties));
        assertEquals("[00:01.00]Hello", api.getLyric("songMID").block());
    }

    @Test void cookieIsForwardedAsConfiguredAccountOverEveryRoute() {
        var properties = properties();
        properties.getQqmusic().setCookie("musicid=999; musickey=accountA");
        var credentials = new QqMusicCredentialService(properties);
        var api = api(properties, client((request, ignored) -> {
            assertEquals("musicid=999; musickey=accountA", request.headers().getFirst("Cookie"));
            assertFalse(request.url().toString().contains("accountA"));
            return Mono.just(data(json("{\"song\":[" + TRACK + "]}")));
        }), credentials);
        assertEquals(1, api.searchMusic("Song").block().size());
        assertTrue(api.isCredentialConfigured());
    }

    @Test void userBindingReadsCreatedAndFavouritePlaylists() {
        var properties = properties();
        List<String> paths = new ArrayList<>();
        var api = api(properties, client((request, ignored) -> {
            String path = request.url().getPath();
            paths.add(path);
            return switch (path) {
                case "/user/123/created_songlists" -> Mono.just(data(json(
                        "{\"playlists\":[{\"id\":11,\"title\":\"Created one\",\"songnum\":1},{\"id\":12,\"title\":\"Created two\",\"songnum\":2}]}")));
                case "/user/oKEA/fav/songlists" -> Mono.just(data(json(
                        "{\"playlists\":[{\"id\":21,\"title\":\"Collected\",\"songnum\":3}],\"total\":1,\"hasmore\":0}")));
                default -> throw new AssertionError(path);
            };
        }), new QqMusicCredentialService(properties));
        var page = api.publicCollections(user(), "playlists", 0, 10).block();
        assertEquals(List.of("11", "12", "21"), page.items().stream().map(p -> p.id()).toList());
        assertNull(page.nextOffset());
        assertEquals(List.of("/user/123/created_songlists", "/user/oKEA/fav/songlists"), paths);
        assertEquals("Collected", page.items().getLast().name());
        assertEquals("qqmusic", page.items().getLast().platform());
    }

    @Test void userBindingPagesAcrossCreatedAndFavouritePlaylists() {
        var properties = properties();
        var api = api(properties, client((request, ignored) -> {
            if (request.url().getPath().equals("/user/123/created_songlists")) {
                return Mono.just(data(json("{\"playlists\":[{\"id\":11,\"title\":\"Created\",\"songnum\":1},"
                        + "{\"id\":12,\"title\":\"Created two\",\"songnum\":1}]}")));
            }
            assertTrue(query(request).contains("page=1"));
            return Mono.just(data(json("{\"playlists\":[{\"id\":21,\"title\":\"A\",\"songnum\":1},"
                    + "{\"id\":22,\"title\":\"B\",\"songnum\":1}],\"total\":2}")));
        }), new QqMusicCredentialService(properties));
        var first = api.publicCollections(user(), "playlists", 1, 2).block();
        assertEquals(List.of("12", "21"), first.items().stream().map(p -> p.id()).toList());
        assertEquals(3, first.nextOffset());
        var second = api.publicCollections(user(), "playlists", 3, 2).block();
        assertEquals(List.of("22"), second.items().stream().map(p -> p.id()).toList());
        assertNull(second.nextOffset());
    }

    @Test void userWithoutEncryptedIdStillReadsCreatedPlaylists() {
        var properties = properties();
        var api = api(properties, client((request, ignored) -> {
            assertEquals("/user/123/created_songlists", request.url().getPath());
            return Mono.just(data(json("{\"playlists\":[{\"id\":11,\"title\":\"Created\",\"songnum\":1}]}")));
        }), new QqMusicCredentialService(properties));
        var page = api.publicCollections(reference("123", null), "playlists", 0, 10).block();
        assertEquals(1, page.items().size());
        assertNull(page.nextOffset());
    }

    @Test void searchUsersEncodesBothIdentifiersIntoOpaqueReference() {
        var properties = properties();
        var api = api(properties, client((request, ignored) -> {
            assertEquals("/search/search_by_type", request.url().getPath());
            assertTrue(query(request).contains("search_type=8"));
            return Mono.just(data(json("{\"user\":[{\"uin\":\"123\",\"encrypt_uin\":\"oKEA\","
                    + "\"title\":\"<em>Public</em>\",\"pic\":\"https://y.qq.com/avatar\"}]}")));
        }), new QqMusicCredentialService(properties));
        var user = api.searchUsers("Public").block().getFirst();
        assertEquals("Public", user.name());
        String payload = new String(Base64.getUrlDecoder().decode(user.id().substring(3)), StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"uin\":\"123\""));
        assertTrue(payload.contains("\"euin\":\"oKEA\""));
    }

    @Test void containerPagingCrossesNativePageBoundaryWithoutLosingSongs() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties);
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
        assertEquals("songMID95", first.items().getFirst().id());
        assertEquals("songMID114", first.items().getLast().id());
        assertEquals(115, first.nextOffset());
        var last = api.containerPage("playlist", "42", 145, 20).block();
        assertEquals(5, last.items().size());
        assertNull(last.nextOffset());
    }

    @Test void albumCollectsEveryPageWithoutArtificialLimit() {
        var properties = properties();
        var api = api(properties, client((request, ignored) -> {
            if (request.url().getPath().endsWith("/detail")) {
                return Mono.just(data(json("{\"album\":{\"mid\":\"albumMID\",\"name\":\"Album\"},\"singers\":[]}")));
            }
            int page = query(request).contains("page=2") ? 2 : 1;
            var songs = MAPPER.createArrayNode();
            for (int index = (page - 1) * 100; index < Math.min(page * 100, 101); index++) {
                var song = (com.fasterxml.jackson.databind.node.ObjectNode) json(TRACK).deepCopy();
                song.put("mid", "songMID" + index);
                songs.add(song);
            }
            var body = MAPPER.createObjectNode().put("album_mid", "albumMID").put("total_num", 101);
            body.set("song_list", songs);
            return Mono.just(data(body));
        }), new QqMusicCredentialService(properties));
        var album = api.getAlbum("albumMID").block();
        assertEquals(101, album.trackCount());
        assertEquals("songMID100", album.songs().getLast().id());
    }

    @Test void mediaUsesNativeFileTypeAndKeepsTimelineAcrossQualities() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties);
        var api = api(properties, client((request, ignored) -> {
            if (request.url().getPath().endsWith("/detail")) return Mono.just(data(json("{\"track\":" + TRACK + "}")));
            assertTrue(query(request).contains("media_mid=mediaMID"));
            String quality = query(request).contains("file_type=7") ? "FLAC"
                    : query(request).contains("file_type=12") ? "MP3_320" : "MP3_128";
            return Mono.just(data(media(quality, "mediaMID")));
        }), credentials);
        var standard = api.resolveMedia("songMID", MediaConsumer.BROWSER).block();
        assertEquals(".mp3", standard.extension());
        assertTrue(standard.url().startsWith("https://isure.stream.qqmusic.qq.com/M800mediaMID.mp3"));
        assertEquals("MP3_320:M800mediaMID.mp3", standard.representationKey());
        properties.getQqmusic().setQuality("FLAC");
        var flac = api.resolveMedia("songMID", MediaConsumer.SERVER).block();
        assertEquals(".flac", flac.extension());
        assertEquals("audio/flac", flac.mimeType());
        assertEquals(standard.timelineKey(), flac.timelineKey());
        assertNotEquals(standard.representationKey(), flac.representationKey());
        assertEquals(".mp3", api.resolveFallbackMedia("songMID").block().extension());
    }

    @Test void mediaAcceptsUpstreamAuthorisationWithoutReimplementingItsPolicy() {
        var properties = properties();
        var api = api(properties, client((request, ignored) -> {
            if (request.url().getPath().endsWith("/detail")) return Mono.just(data(json("{\"track\":" + TRACK + "}")));
            return Mono.just(data(json("{\"expiration\":1200,\"data\":[{\"mid\":\"songMID\","
                    + "\"filename\":\"M800mediaMID.m4a\",\"purl\":\"M800mediaMID.m4a?vkey=v\",\"vkey\":\"v\","
                    + "\"ekey\":\"encrypted-but-upstream-authorised\",\"result\":0}]}")));
        }), new QqMusicCredentialService(properties));
        var media = api.resolveMedia("songMID", MediaConsumer.SERVER).block();
        assertNotNull(media);
        assertEquals(".m4a", media.extension());
        assertEquals("audio/mp4", media.mimeType());
        assertTrue(media.url().contains("M800mediaMID.m4a"));
    }

    @Test void mediaWithoutUsablePurlIsReportedAsUnavailable() {
        var properties = properties();
        var api = api(properties, client((request, ignored) -> {
            if (request.url().getPath().endsWith("/detail")) return Mono.just(data(json("{\"track\":" + TRACK + "}")));
            return Mono.just(data(json("{\"data\":[{\"mid\":\"songMID\",\"filename\":\"M800mediaMID.mp3\","
                    + "\"purl\":\"\",\"ekey\":\"\",\"result\":104003}]}")));
        }), new QqMusicCredentialService(properties));
        assertTrue(assertThrows(ApiRequestException.class,
                () -> api.resolveMedia("songMID", MediaConsumer.SERVER).block()).getMessage().startsWith("MEDIA_UNAVAILABLE"));
    }

    @Test void encryptedNativeContainerStaysUnsupported() {
        var properties = properties();
        var api = api(properties, client((request, ignored) -> {
            if (request.url().getPath().endsWith("/detail")) return Mono.just(data(json("{\"track\":" + TRACK + "}")));
            return Mono.just(data(json("{\"data\":[{\"mid\":\"songMID\",\"filename\":\"F0M0mediaMID.mflac\","
                    + "\"purl\":\"F0M0mediaMID.mflac\",\"ekey\":\"k\",\"result\":0}]}")));
        }), new QqMusicCredentialService(properties));
        assertTrue(assertThrows(ApiRequestException.class,
                () -> api.resolveMedia("songMID", MediaConsumer.SERVER).block()).getMessage().startsWith("MEDIA_UNAVAILABLE"));
    }

    @Test void unknownQualityFallsBackTo320InsteadOfFailing() {
        var properties = properties();
        properties.getQqmusic().setQuality("turbo");
        var api = api(properties, client((request, ignored) -> {
            if (request.url().getPath().endsWith("/detail")) return Mono.just(data(json("{\"track\":" + TRACK + "}")));
            assertTrue(query(request).contains("file_type=12"));
            return Mono.just(data(media("FLAC", "mediaMID")));
        }), new QqMusicCredentialService(properties));
        assertEquals(".flac", api.resolveMedia("songMID", MediaConsumer.SERVER).block().extension());
    }

    @Test void upstreamBusinessFailureYieldsNoSongsInsteadOfEchoingTheEnvelope() {
        var properties = properties();
        var api = api(properties, client((request, ignored) ->
                Mono.just(response(HttpStatus.OK, "{\"code\":-1,\"msg\":\"private-secret\"}"))),
                new QqMusicCredentialService(properties));
        assertEquals(0, api.searchMusic("song").block().size());
    }

    @Test void unconfiguredSourceSendsNoCookieHeader() {
        var properties = properties();
        var api = api(properties, client((request, ignored) -> {
            assertNull(request.headers().getFirst("Cookie"));
            assertNull(request.headers().getFirst("Authorization"));
            return Mono.just(data(json("{\"song\":[]}")));
        }), new QqMusicCredentialService(properties));
        assertFalse(api.isCredentialConfigured());
        assertEquals(0, api.searchMusic("song").block().size());
    }
}
