package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.exception.ApiRequestException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

@Service
public class QqMusicGateway {
    private static final Set<String> PUBLIC = Set.of("search", "track", "lyrics", "users", "collections", "container", "tracks", "album", "track-album");
    private static final Set<String> ERRORS = Set.of("INVALID_INPUT", "INVALID_CREDENTIAL", "PUBLIC_CREDENTIAL_FORBIDDEN",
            "USER_ID_UNAVAILABLE", "CAPABILITY_UNAVAILABLE", "SOURCE_AUTH_REQUIRED", "SOURCE_RATE_LIMITED", "UNAUTHORIZED",
            "BODY_TOO_LARGE", "METHOD_NOT_ALLOWED", "REQUEST_TIMEOUT", "UPSTREAM_TIMEOUT", "UPSTREAM_UNAVAILABLE",
            "UPSTREAM_INVALID", "UPSTREAM_REJECTED", "UPSTREAM_RESPONSE_TOO_LARGE", "COLLECTION_INCOMPLETE",
            "COLLECTION_CHANGED", "COLLECTION_NOT_PUBLIC", "PAGINATION_STALLED", "MEDIA_UNAVAILABLE", "MEDIA_CONTENT_CHANGED");
    private final WebClient client;
    private final AppProperties properties;

    public QqMusicGateway(WebClient client, AppProperties properties) {
        this.client = client.mutate().codecs(config -> config.defaultCodecs().maxInMemorySize(2 * 1024 * 1024)).build();
        this.properties = properties;
    }

    public Mono<JsonNode> publicRequest(String operation, Map<String, Object> params) {
        if (!PUBLIC.contains(operation)) return Mono.error(new ApiRequestException("INVALID_REQUEST: 非公开QQ音乐操作"));
        return request(operation, Map.of("params", params));
    }

    public Mono<JsonNode> authenticatedRequest(String operation, Map<String, Object> params, QqMusicCredentialService.Snapshot snapshot) {
        if (!Set.of("media", "media-fallback", "fm").contains(operation)) {
            return Mono.error(new ApiRequestException("INVALID_REQUEST: 无效QQ音乐操作"));
        }
        snapshot.requireValid();
        if ("fm".equals(operation) && !snapshot.configured()) {
            return Mono.error(new ApiRequestException("RECOMMENDATION_AUTH_REQUIRED: 请配置QQ音乐凭据"));
        }
        Map<String, Object> body = snapshot.configured() ? Map.of("params", params, "credential", snapshot.credential()) : Map.of("params", params);
        return request(operation, body);
    }

    private Mono<JsonNode> request(String operation, Map<String, Object> body) {
        return Mono.defer(() -> {
            String token = properties.getQqmusic().getGatewayToken();
            if (token == null || token.length() < 16 || token.length() > 512 || !token.chars().allMatch(value -> value >= 33 && value <= 126)) {
                return Mono.error(new ApiRequestException("GATEWAY_NOT_CONFIGURED: QQ音乐网关令牌未配置"));
            }
            URI base;
            try {
                base = URI.create(properties.getQqmusic().getBaseUrl());
                if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null || base.getRawUserInfo() != null
                        || base.getRawQuery() != null || base.getRawFragment() != null) throw new IllegalArgumentException();
            } catch (Exception error) { return Mono.error(new ApiRequestException("GATEWAY_NOT_CONFIGURED: QQ音乐网关地址无效")); }
            String address = base.toString().replaceAll("/+$", "") + "/v1/" + operation;
            return client.post().uri(address).headers(headers -> headers.setBearerAuth(token))
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(body)
                    .exchangeToMono(response -> response.bodyToMono(JsonNode.class)
                            .switchIfEmpty(Mono.error(invalidResponse()))
                            .flatMap(root -> {
                                if (!response.statusCode().is2xxSuccessful()) {
                                    String code = root.path("error").path("code").asText("");
                                    return Mono.error(new ApiRequestException((ERRORS.contains(code) ? code : "UPSTREAM_UNAVAILABLE") + ": QQ音乐请求失败"));
                                }
                                if (!root.isObject() || root.has("error") || !root.hasNonNull("data")) return Mono.error(invalidResponse());
                                return Mono.just(root.get("data"));
                            }));
        }).timeout(Duration.ofSeconds(20))
                .onErrorMap(error -> error instanceof ApiRequestException ? error : new ApiRequestException("UPSTREAM_UNAVAILABLE: QQ音乐网关请求失败"));
    }

    private ApiRequestException invalidResponse() {
        return new ApiRequestException("UPSTREAM_UNAVAILABLE: QQ音乐网关响应无效");
    }
}
