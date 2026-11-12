package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.thornex.musicparty.config.AppProperties;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

final class QqMusicTestSupport {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String TRACK = "{\"id\":\"songMID\",\"name\":\"Song\",\"artists\":[\"Artist\"],\"duration\":180000,\"platform\":\"qqmusic\",\"coverUrl\":\"https://cover.test/image\",\"externalUrl\":\"https://y.qq.com/n/ryqq/songDetail/songMID\"}";

    static AppProperties properties() {
        var properties = new AppProperties();
        properties.getQqmusic().setBaseUrl("http://gateway.test:8000");
        properties.getQqmusic().setGatewayToken("private-gateway-token");
        properties.getQqmusic().setQuality("MP3_128");
        return properties;
    }

    static JsonNode json(String value) {
        try { return MAPPER.readTree(value); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    static ClientResponse data(JsonNode value) {
        return response(HttpStatus.OK, "{\"data\":" + value + "}");
    }

    static ClientResponse response(HttpStatus status, String value) {
        return ClientResponse.create(status).header("Content-Type", "application/json").body(value).build();
    }

    static WebClient client(BiFunction<ClientRequest, JsonNode, Mono<ClientResponse>> handler) {
        return WebClient.builder().exchangeFunction(request -> {
            var output = new MockClientHttpRequest(request.method(), request.url());
            var context = new BodyInserter.Context() {
                @Override public List<HttpMessageWriter<?>> messageWriters() { return HandlerStrategies.withDefaults().messageWriters(); }
                @Override public Optional<ServerHttpRequest> serverRequest() { return Optional.empty(); }
                @Override public Map<String, Object> hints() { return Map.of(); }
            };
            return request.body().insert(output, context).then(Mono.defer(output::getBodyAsString))
                    .flatMap(body -> handler.apply(request, json(body)));
        }).build();
    }

    static QqMusicApiService api(AppProperties properties, WebClient client, QqMusicCredentialService credentials) {
        return new QqMusicApiService(new QqMusicGateway(client, properties), credentials, properties, MAPPER);
    }
}
