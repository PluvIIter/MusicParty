package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.exception.ApiRequestException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

@Service
public class QqMusicWebClient {
    private final WebClient client;
    private final AppProperties properties;
    private final QqMusicCredentialService credentials;

    public QqMusicWebClient(WebClient client, AppProperties properties, QqMusicCredentialService credentials) {
        this.client = client.mutate().defaultHeaders(headers -> {
                    headers.remove("Cookie");
                    headers.remove("Authorization");
                }).defaultCookies(cookies -> cookies.clear())
                .codecs(config -> config.defaultCodecs().maxInMemorySize(4 * 1024 * 1024)).build();
        this.properties = properties;
        this.credentials = credentials;
    }

    public Mono<JsonNode> publicGet(String path, Map<String, ?> query) {
        return get(path, query, null);
    }

    public Mono<JsonNode> privateGet(String path, Map<String, ?> query, QqMusicCredentialService.Snapshot snapshot, boolean required) {
        return Mono.defer(() -> {
            credentials.requireCurrent(snapshot);
            snapshot.requireValid();
            if (required && !snapshot.configured()) return Mono.error(new ApiRequestException("SOURCE_AUTH_REQUIRED: 请配置QQ音乐凭据"));
            return get(path, query, snapshot);
        });
    }

    private Mono<JsonNode> get(String path, Map<String, ?> query, QqMusicCredentialService.Snapshot snapshot) {
        return Mono.defer(() -> {
            if (snapshot != null) credentials.requireCurrent(snapshot);
            URI base;
            try {
                base = URI.create(properties.getQqmusic().getBaseUrl());
                if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null || base.getRawUserInfo() != null
                        || base.getRawQuery() != null || base.getRawFragment() != null || !path.startsWith("/")) throw new IllegalArgumentException();
            } catch (Exception error) { return Mono.error(new ApiRequestException("SOURCE_NOT_CONFIGURED: QQ音乐Web地址无效")); }
            var uri = UriComponentsBuilder.fromUriString(base.toString().replaceAll("/+$", "") + path);
            Map<String, Object> variables = new java.util.LinkedHashMap<>();
            query.forEach((key, value) -> {
                String variable = "query" + variables.size();
                uri.queryParam(key, "{" + variable + "}");
                variables.put(variable, value);
            });
            return client.get().uri(uri.encode().buildAndExpand(variables).toUri()).headers(headers -> {
                        headers.remove("Cookie");
                        headers.remove("Authorization");
                        if (snapshot != null && snapshot.configured()) headers.set("Cookie", snapshot.cookieHeader());
                    })
                    .exchangeToMono(response -> response.bodyToMono(JsonNode.class)
                            .switchIfEmpty(Mono.error(invalid()))
                            .flatMap(root -> {
                                if (snapshot != null) credentials.requireCurrent(snapshot);
                                if (!response.statusCode().is2xxSuccessful()) {
                                    String code = switch (response.statusCode().value()) {
                                        case 401, 403 -> "SOURCE_AUTH_REQUIRED";
                                        case 429 -> "SOURCE_RATE_LIMITED";
                                        case 408, 504 -> "UPSTREAM_TIMEOUT";
                                        default -> "UPSTREAM_UNAVAILABLE";
                                    };
                                    return Mono.error(new ApiRequestException(code + ": QQ音乐Web请求失败"));
                                }
                                if (!root.isObject() || !root.path("code").isIntegralNumber() || root.path("code").intValue() != 0
                                        || !root.hasNonNull("data")) return Mono.error(invalid());
                                return Mono.just(root.get("data"));
                            }));
        }).timeout(Duration.ofSeconds(20))
                .onErrorMap(error -> error instanceof ApiRequestException ? error : new ApiRequestException(error instanceof java.util.concurrent.TimeoutException
                        ? "UPSTREAM_TIMEOUT: QQ音乐Web请求超时" : "UPSTREAM_UNAVAILABLE: QQ音乐Web请求失败"));
    }

    private ApiRequestException invalid() { return new ApiRequestException("UPSTREAM_UNAVAILABLE: QQ音乐Web响应无效"); }
}
