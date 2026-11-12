package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.*;
import org.thornex.musicparty.config.AppProperties;
import reactor.core.publisher.Mono;

import java.util.function.BiFunction;

final class QqMusicTestSupport {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String TRACK = "{\"id\":12345,\"mid\":\"songMID\",\"name\":\"Song\",\"type\":1,\"singer\":[{\"name\":\"Artist\"}],\"interval\":180,\"album\":{\"mid\":\"albumMID\",\"name\":\"Album\"},\"file\":{\"media_mid\":\"mediaMID\",\"size_128mp3\":12345,\"size_320mp3\":23456,\"size_flac\":34567}}";

    static AppProperties properties() {
        var properties = new AppProperties();
        properties.getQqmusic().setBaseUrl("http://web.test:8000");
        properties.getQqmusic().setQuality("MP3_128");
        return properties;
    }

    static JsonNode json(String value) {
        try { return MAPPER.readTree(value); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    static ClientResponse data(JsonNode value) {
        return response(HttpStatus.OK, "{\"code\":0,\"msg\":\"ok\",\"data\":" + value + "}");
    }

    static ClientResponse response(HttpStatus status, String value) {
        return ClientResponse.create(status).header("Content-Type", "application/json").body(value).build();
    }

    static WebClient client(BiFunction<ClientRequest, JsonNode, Mono<ClientResponse>> handler) {
        return WebClient.builder().exchangeFunction(request -> handler.apply(request, MAPPER.createObjectNode())).build();
    }

    static String timeline(String songMid, String mediaMid, long duration) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((songMid + ":" + mediaMid + ":" + duration + ":full").getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    static JsonNode media(String quality, String mediaMid) {
        String extension = quality.equals("FLAC") ? ".flac" : ".mp3";
        String filename = (quality.equals("FLAC") ? "F000" : quality.equals("MP3_320") ? "M800" : "M500") + mediaMid + extension;
        return json("{\"expiration\":600,\"data\":[{\"mid\":\"songMID\",\"filename\":\"" + filename
                + "\",\"purl\":\"" + filename + "?vkey=authorized\",\"vkey\":\"authorized\",\"ekey\":\"\",\"result\":0}]}");
    }

    static QqMusicApiService api(AppProperties properties, WebClient client, QqMusicCredentialService credentials) {
        return new QqMusicApiService(new QqMusicWebClient(client, properties, credentials), credentials, properties, MAPPER);
    }
}
