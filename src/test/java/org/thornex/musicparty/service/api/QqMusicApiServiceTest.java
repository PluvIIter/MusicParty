package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.media.MediaConsumer;
import org.thornex.musicparty.source.DeliveryMode;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.thornex.musicparty.service.api.QqMusicTestSupport.*;

class QqMusicApiServiceTest {
    private String user(String body) {
        return "q1." + Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8));
    }

    private org.springframework.web.reactive.function.client.WebClient mediaClient(java.util.function.BiFunction<org.springframework.web.reactive.function.client.ClientRequest, com.fasterxml.jackson.databind.JsonNode, Mono<org.springframework.web.reactive.function.client.ClientResponse>> handler) {
        return client((request, body) -> request.url().getPath().equals("/v1/track")
                ? Mono.just(data(json(TRACK))) : handler.apply(request, body));
    }

    private String timeline() {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest("songMID:mediaMID:180000:full".getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private String media(String quality, String expiry, String headers, boolean browserSafe) {
        boolean flac = "FLAC".equals(quality);
        return "{\"id\":\"songMID\",\"mediaMid\":\"mediaMID\",\"representationKey\":\"" + quality + ":mediaMID:12345\",\"url\":\"https://cdn.test/song\",\"mimeType\":\""
                + (flac ? "audio/flac" : "audio/mpeg") + "\",\"extension\":\"" + (flac ? ".flac" : ".mp3")
                + "\",\"expiresAt\":\"" + expiry + "\",\"browserSafe\":" + browserSafe + ",\"headers\":" + headers + ",\"timelineKey\":\"" + timeline() + "\"}";
    }

    @Test
    void publicParityOperationsNeverIncludeConfiguredCredentialAndUseTypedIdentity() {
        var properties = properties();
        properties.getQqmusic().setCookie("{\"musicid\":\"123\",\"musickey\":\"account-secret\"}");
        List<String> operations = new ArrayList<>();
        String target = user("{\"uin\":\"9007199254740993\",\"euin\":\"oKEA7i4kowv57n**\"}");
        var client = client((request, body) -> {
            assertEquals("Bearer private-gateway-token", request.headers().getFirst("Authorization"));
            assertNull(request.url().getQuery());
            assertFalse(body.has("credential"));
            String op = request.url().getPath().substring(4);
            operations.add(op);
            String value = switch (op) {
                case "search" -> "[" + TRACK + "]";
                case "track" -> TRACK;
                case "lyrics" -> "\"[00:00.00]Lyric\"";
                case "users" -> "[{\"id\":\"" + target + "\",\"name\":\"Public\",\"avatarUrl\":null,\"platform\":\"qqmusic\"}]";
                case "collections" -> "{\"items\":[{\"id\":\"42\",\"name\":\"Playlist\",\"coverImgUrl\":null,\"trackCount\":1,\"platform\":\"qqmusic\"}],\"nextOffset\":null,\"total\":1}";
                case "container" -> "{\"id\":\"42\",\"name\":\"Playlist\",\"coverImgUrl\":null,\"trackCount\":1,\"platform\":\"qqmusic\"}";
                case "tracks" -> "{\"items\":[" + TRACK + "],\"nextOffset\":null,\"total\":1}";
                case "album", "track-album" -> "{\"id\":\"albumMID\",\"name\":\"Album\",\"coverUrl\":null,\"trackCount\":1,\"songs\":[" + TRACK + "]}";
                default -> throw new AssertionError(op);
            };
            return Mono.just(data(json(value)));
        });
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        var api = api(properties, client, credentials);
        assertTrue(api.defaultDisabled());
        assertEquals(DeliveryMode.SERVER_DOWNLOAD, api.defaultDeliveryMode());
        assertTrue(api.descriptor().capabilities().download());
        assertTrue(api.descriptor().supportedModes().containsAll(List.of(DeliveryMode.DIRECT_URL, DeliveryMode.SERVER_DOWNLOAD)));
        assertEquals("songMID", api.searchMusic("Song").block().getFirst().id());
        assertEquals("Song", api.getMusic("songMID").block().name());
        assertEquals("[00:00.00]Lyric", api.getLyric("songMID").block());
        assertEquals(target, api.searchUsers("Public").block().getFirst().id());
        assertEquals("42", api.publicCollections(target, "playlists", 0, 20).block().items().getFirst().id());
        assertEquals("Playlist", api.containerDetail("playlist", "42").block().name());
        assertEquals("songMID", api.containerPage("playlist", "42", 0, 20).block().items().getFirst().id());
        assertEquals("albumMID", api.getAlbum("albumMID").block().id());
        assertEquals("albumMID", api.getAlbumForSong("songMID").block().id());
        assertEquals(9, operations.size());
    }

    @Test
    void emptyPublicResultsAreLegitimateButPartialAlbumsWrongIdentityAndDurationsAreRejected() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        for (String value : List.of(TRACK.replace("songMID", "otherMID"), TRACK.replace("qqmusic", "netease"),
                TRACK.replace("180000", "0"), TRACK.replace("180000", "1.5"))) {
            var api = api(properties, client((request, body) -> Mono.just(data(json(value)))), credentials);
            assertThrows(ApiRequestException.class, () -> api.getMusic("songMID").block());
        }
        var empty = api(properties, client((request, body) -> Mono.just(data(json("[]")))), credentials);
        assertTrue(empty.searchMusic("query").block().isEmpty());
        assertTrue(empty.searchUsers("query").block().isEmpty());
        var lyrics = api(properties, client((request, body) -> Mono.just(data(json("\"\"")))), credentials);
        assertEquals("", lyrics.getLyric("songMID").block());
        var partial = api(properties, client((request, body) -> Mono.just(data(json(
                "{\"id\":\"albumMID\",\"name\":\"Album\",\"trackCount\":2,\"songs\":[" + TRACK + "]}")))), credentials);
        assertThrows(ApiRequestException.class, () -> partial.getAlbum("albumMID").block());
    }

    @Test
    void pageUsesRawUpstreamProgressEvenWhenFilteredEmptyAndRejectsInvalidContinuation() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        var api = api(properties, client((request, body) -> {
            assertEquals(20, body.path("params").path("offset").intValue());
            return Mono.just(data(json("{\"items\":[],\"nextOffset\":40,\"total\":60}")));
        }), credentials);
        assertEquals(40, api.containerPage("playlist", "42", 20, 20).block().nextOffset());
        for (String next : List.of("20", "41", "60", "-1", "\"40\"")) {
            var invalid = api(properties, client((request, body) -> Mono.just(data(json(
                    "{\"items\":[],\"nextOffset\":" + next + ",\"total\":60}")))), credentials);
            assertThrows(ApiRequestException.class, () -> invalid.containerPage("playlist", "42", 20, 20).block());
        }
    }

    @Test
    void invalidTargetsTypesAndLimitsNeverContactGateway() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        var api = api(properties, client((request, body) -> { fail("invalid request reached gateway"); return Mono.empty(); }), credentials);
        assertThrows(ApiRequestException.class, () -> api.publicCollections("nickname", "playlists", 0, 20).block());
        assertThrows(ApiRequestException.class, () -> api.publicCollections(user("{\"euin\":\"opaque\"}"), "playlists", 0, 20).block());
        assertThrows(ApiRequestException.class, () -> api.publicCollections(user("{\"uin\":\"1\",\"credential\":\"secret\"}"), "playlists", 0, 20).block());
        assertThrows(ApiRequestException.class, () -> api.publicCollections(user("{\"uin\":1}"), "playlists", 0, 20).block());
        assertThrows(ApiRequestException.class, () -> api.containerDetail("album", "42").block());
        assertThrows(ApiRequestException.class, () -> api.containerPage("playlist", "42", 0, 101).block());
        assertThrows(ApiRequestException.class, () -> api.containerPage("playlist", "42", -1, 20).block());
        assertThrows(ApiRequestException.class, () -> api.getMusic("bad/id").block());
        assertThrows(ApiRequestException.class, () -> api.getMusic("123456").block());
        assertThrows(ApiRequestException.class, () -> api.searchMusic(" ").block());
    }

    @Test
    void mediaUsesSingleCredentialSnapshotAndRealFormatWithSeparateDownloadFallback() {
        var properties = properties();
        properties.getQqmusic().setQuality("FLAC");
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        credentials.updateCookie("{\"musicid\":123,\"musickey\":\"secret\"}");
        List<String> operations = new ArrayList<>();
        var api = api(properties, mediaClient((request, body) -> {
            assertEquals("123", body.path("credential").path("musicid").textValue());
            assertEquals("secret", body.path("credential").path("musickey").textValue());
            assertFalse(request.url().toString().contains("secret"));
            String op = request.url().getPath();
            operations.add(op);
            String quality = body.path("params").path("quality").asText();
            return Mono.just(data(json(media(quality, Instant.now().plusSeconds(600).toString(), "{\"Referer\":\"https://y.qq.com/\"}", false))));
        }), credentials);
        assertFalse(api.descriptor().capabilities().download());
        var primary = api.resolveMedia("songMID", MediaConsumer.SERVER).block();
        var fallback = api.resolveFallbackMedia("songMID").block();
        assertEquals(".flac", primary.extension());
        assertEquals(".mp3", fallback.extension());
        assertNotEquals(primary.representationKey(), fallback.representationKey());
        assertEquals(primary.timelineKey(), fallback.timelineKey());
        assertEquals(Map.of("Referer", "https://y.qq.com/"), primary.headers());
        assertEquals(List.of("/v1/media", "/v1/media-fallback"), operations);
    }

    @Test
    void downloadFallbackRejectsSameRepresentationOrDifferentTimeline() {
        var properties = properties();
        properties.getQqmusic().setQuality("MP3_320");
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        for (boolean sameRepresentation : List.of(true, false)) {
            var api = api(properties, mediaClient((request, body) -> {
                String quality = body.path("params").path("quality").asText();
                String value = media(quality, Instant.now().plusSeconds(600).toString(), "{}", true);
                if (request.url().getPath().endsWith("media-fallback")) {
                    value = sameRepresentation ? value.replace("MP3_128:mediaMID:12345", "MP3_320:mediaMID:12345")
                            : value.replace(timeline(), "0".repeat(64));
                }
                return Mono.just(data(json(value)));
            }), credentials);
            var error = assertThrows(ApiRequestException.class, () -> api.resolveFallbackMedia("songMID").block());
            assertTrue(error.getMessage().startsWith(sameRepresentation ? "UPSTREAM_UNAVAILABLE" : "MEDIA_CONTENT_CHANGED"));
        }
    }

    @Test
    void unavailableFlacPrimaryDoesNotPreventIndependentMp3Fallback() {
        var properties = properties();
        properties.getQqmusic().setQuality("FLAC");
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        var api = api(properties, mediaClient((request, body) -> request.url().getPath().endsWith("media-fallback")
                ? Mono.just(data(json(media("MP3_128", Instant.now().plusSeconds(600).toString(), "{}", true))))
                : Mono.just(response(HttpStatus.BAD_GATEWAY, "{\"error\":{\"code\":\"MEDIA_UNAVAILABLE\"}}"))), credentials);
        assertThrows(ApiRequestException.class, () -> api.resolveMedia("songMID", MediaConsumer.SERVER).block());
        assertEquals(".mp3", api.resolveFallbackMedia("songMID").block().extension());
    }

    @Test
    void completeAlbumCanContainMoreThanOneThousandSongsWithoutPartialSuccess() {
        var songs = MAPPER.createArrayNode();
        for (int index = 0; index < 1001; index++) {
            var song = json(TRACK).deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) song).put("id", "songMID" + index);
            songs.add(song);
        }
        var album = MAPPER.createObjectNode().put("id", "albumMID").put("name", "Album").put("trackCount", 1001);
        album.set("songs", songs);
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        var api = api(properties, client((request, body) -> Mono.just(data(album))), credentials);
        assertEquals(1001, api.getAlbum("albumMID").block().songs().size());
    }

    @Test
    void invalidConfiguredCredentialDoesNotBreakPublicMetadataOrFallbackToAnonymousMedia() {
        var properties = properties();
        properties.getQqmusic().setCookie("invalid-secret");
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        var api = api(properties, client((request, body) -> {
            assertEquals("/v1/track", request.url().getPath());
            assertFalse(body.has("credential"));
            return Mono.just(data(json(TRACK)));
        }), credentials);
        assertFalse(api.isCredentialConfigured());
        assertEquals("songMID", api.getMusic("songMID").block().id());
        assertTrue(assertThrows(ApiRequestException.class, () -> api.resolveMedia("songMID", MediaConsumer.SERVER).block())
                .getMessage().startsWith("INVALID_CREDENTIAL"));
        assertTrue(assertThrows(ApiRequestException.class, () -> api.recommendations().block()).getMessage().startsWith("INVALID_CREDENTIAL"));
    }

    @Test
    void anonymousBrowserMediaIsAllowedButUnsafeExpiredAndWrongFormatAreRejected() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        String valid = media("MP3_128", Instant.now().plusSeconds(600).toString(), "{}", true);
        var api = api(properties, mediaClient((request, body) -> {
            assertFalse(body.has("credential"));
            return Mono.just(data(json(valid)));
        }), credentials);
        assertTrue(api.resolveMedia("songMID", MediaConsumer.BROWSER).block().browserSafe());
        assertThrows(ApiRequestException.class, () -> api.resolveFallbackMedia("songMID").block());
        for (String value : List.of(valid.replace("songMID", "different"), valid.replace(".mp3", ".mflac"),
                valid.replace("audio/mpeg", "application/octet-stream"), valid.replace("\"browserSafe\":true", "\"browserSafe\":false"),
                valid.replace("\"headers\":{}", "\"headers\":{\"Cookie\":\"secret\"}"),
                media("MP3_128", Instant.now().minusSeconds(1).toString(), "{}", true))) {
            var invalid = api(properties, mediaClient((request, body) -> Mono.just(data(json(value)))), credentials);
            assertThrows(ApiRequestException.class, () -> invalid.resolveMedia("songMID", MediaConsumer.BROWSER).block());
        }
    }

    @Test
    void credentialChangeWhileMetadataWaitsNeverDispatchesOldPrivateMedia() {
        for (String replacement : List.of("", "{\"musicid\":123,\"musickey\":\"new\"}")) {
            for (boolean metadataSucceeds : List.of(true, false)) {
                var properties = properties();
                var credentials = new QqMusicCredentialService(properties, MAPPER);
                credentials.updateCookie("{\"musicid\":123,\"musickey\":\"old\"}");
                Sinks.One<org.springframework.web.reactive.function.client.ClientResponse> metadata = Sinks.one();
                var privateRequests = new java.util.concurrent.atomic.AtomicInteger();
                var api = api(properties, client((request, body) -> {
                    if (request.url().getPath().equals("/v1/track")) {
                        assertFalse(body.has("credential"));
                        return metadata.asMono();
                    }
                    privateRequests.incrementAndGet();
                    return Mono.just(data(json(media("MP3_128", Instant.now().plusSeconds(600).toString(), "{}", true))));
                }), credentials);
                var result = api.resolveMedia("songMID", MediaConsumer.SERVER).toFuture();
                credentials.updateCookie(replacement);
                if (metadataSucceeds) metadata.tryEmitValue(data(json(TRACK)));
                else metadata.tryEmitError(new IllegalStateException("old metadata failed"));
                var failure = assertThrows(java.util.concurrent.CompletionException.class, result::join);
                assertInstanceOf(ApiRequestException.class, failure.getCause());
                if (metadataSucceeds) assertTrue(failure.getCause().getMessage().startsWith("CREDENTIAL_CHANGED"));
                assertEquals(0, privateRequests.get());
            }
        }
    }

    @Test
    void mediaMidIsRequiredMatchesRepresentationAndBindsTimelineAcrossQualities() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        var api = api(properties, mediaClient((request, body) -> Mono.just(data(QqMusicTestSupport.media(
                body.path("params").path("quality").asText(), "mediaMID")))), credentials);
        var first = api.resolveMedia("songMID", MediaConsumer.SERVER).block();
        properties.getQqmusic().setQuality("FLAC");
        var lossless = api.resolveMedia("songMID", MediaConsumer.SERVER).block();
        assertEquals(first.timelineKey(), lossless.timelineKey());
        assertNotEquals(first.representationKey(), lossless.representationKey());
        var other = api(properties, mediaClient((request, body) -> Mono.just(data(QqMusicTestSupport.media("FLAC", "otherMID")))), credentials);
        assertNotEquals(first.timelineKey(), other.resolveMedia("songMID", MediaConsumer.SERVER).block().timelineKey());
        var numeric = api(properties, mediaClient((request, body) -> Mono.just(data(QqMusicTestSupport.media("FLAC", "123456")))), credentials);
        assertEquals(QqMusicTestSupport.timeline("songMID", "123456", 180000), numeric.resolveMedia("songMID", MediaConsumer.SERVER).block().timelineKey());
        for (String mediaMid : List.of("", "bad:id", "bad/path", "bad mid")) {
            var value = QqMusicTestSupport.media("FLAC", "mediaMID");
            ((com.fasterxml.jackson.databind.node.ObjectNode) value).put("mediaMid", mediaMid);
            var invalid = api(properties, mediaClient((request, body) -> Mono.just(data(value))), credentials);
            assertThrows(ApiRequestException.class, () -> invalid.resolveMedia("songMID", MediaConsumer.SERVER).block());
        }
        for (boolean missing : List.of(true, false)) {
            var value = QqMusicTestSupport.media("FLAC", "mediaMID");
            if (missing) ((com.fasterxml.jackson.databind.node.ObjectNode) value).remove("mediaMid");
            else ((com.fasterxml.jackson.databind.node.ObjectNode) value).put("representationKey", "FLAC:otherMID:12345");
            var invalid = api(properties, mediaClient((request, body) -> Mono.just(data(value))), credentials);
            assertThrows(ApiRequestException.class, () -> invalid.resolveMedia("songMID", MediaConsumer.SERVER).block());
        }
    }

    @Test
    void lateMediaOrRecommendationResponsesAfterCredentialUpdateAreDiscarded() {
        var properties = properties();
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        credentials.updateCookie("{\"musicid\":123,\"musickey\":\"old\"}");
        Sinks.One<org.springframework.web.reactive.function.client.ClientResponse> response = Sinks.one();
        var api = api(properties, mediaClient((request, body) -> response.asMono()), credentials);
        var result = api.resolveMedia("songMID", MediaConsumer.BROWSER).toFuture();
        credentials.updateCookie("{\"musicid\":123,\"musickey\":\"new\"}");
        response.tryEmitValue(data(json(media("MP3_128", Instant.now().plusSeconds(600).toString(), "{}", true))));
        assertTrue(result.isCompletedExceptionally());
        Sinks.One<org.springframework.web.reactive.function.client.ClientResponse> fm = Sinks.one();
        var other = api(properties, mediaClient((request, body) -> fm.asMono()), credentials);
        var recommendations = other.recommendations().toFuture();
        credentials.updateCookie("");
        fm.tryEmitValue(data(json("[" + TRACK + "]")));
        assertTrue(recommendations.isCompletedExceptionally());
    }

    @Test
    void missingGatewayTokenAndSanitizedUpstreamErrorsNeverExposeCredentials() {
        var properties = properties();
        properties.getQqmusic().setGatewayToken("");
        var credentials = new QqMusicCredentialService(properties, MAPPER);
        var api = api(properties, mediaClient((request, body) -> { fail("missing token must not contact gateway"); return Mono.empty(); }), credentials);
        assertTrue(assertThrows(ApiRequestException.class, () -> api.searchMusic("Song").block()).getMessage().contains("GATEWAY_NOT_CONFIGURED"));
        properties.getQqmusic().setGatewayToken("valid-gateway-token");
        for (String value : List.of("{\"error\":{\"code\":\"COLLECTION_NOT_PUBLIC\",\"message\":\"private-secret\"}}",
                "{\"error\":{\"code\":\"private-secret\",\"message\":\"private-secret\"}}")) {
            var failing = api(properties, mediaClient((request, body) -> Mono.just(response(HttpStatus.BAD_GATEWAY, value))), credentials);
            var error = assertThrows(ApiRequestException.class, () -> failing.searchMusic("Song").block());
            assertFalse(error.getMessage().contains("private-secret"));
        }
    }
}
