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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * QQ 音乐原生 Web API 客户端：拼 URI、转发当前 Cookie、解出上游 JSON。
 *
 * <p>普通查询只按 HTTP 状态判定失败，业务码由调用方处理；刷新登录态需要读取上游
 * {@code code}，因此另提供保留完整 envelope 的 {@link #getRaw(String, Map)}。</p>
 */
@Service
public class QqMusicWebClient {
    private final WebClient client;
    private final AppProperties properties;
    private final QqMusicCredentialService credentials;

    public QqMusicWebClient(WebClient client, AppProperties properties, QqMusicCredentialService credentials) {
        this.client = client.mutate()
                .defaultHeaders(headers -> {
                    headers.remove("Cookie");
                    headers.remove("Authorization");
                })
                .defaultCookies(cookies -> cookies.clear())
                .codecs(config -> config.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();
        this.properties = properties;
        this.credentials = credentials;
    }

    public Mono<JsonNode> getRaw(String path, Map<String, ?> query) {
        return request(path, query);
    }

    public Mono<JsonNode> get(String path, Map<String, ?> query) {
        return request(path, query).map(root -> root.hasNonNull("data") ? root.get("data") : root);
    }

    private Mono<JsonNode> request(String path, Map<String, ?> query) {
        return Mono.defer(() -> {
            URI target;
            try {
                URI base = URI.create(properties.getQqmusic().getBaseUrl());
                if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                        || base.getRawUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null
                        || !path.startsWith("/")) throw new IllegalArgumentException();
                UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(base.toString().replaceAll("/+$", "") + path);
                Map<String, Object> variables = new LinkedHashMap<>();
                query.forEach((key, value) -> {
                    if (value == null) return;
                    String variable = "query" + variables.size();
                    builder.queryParam(key, "{" + variable + "}");
                    variables.put(variable, value);
                });
                target = builder.encode().buildAndExpand(variables).toUri();
            } catch (Exception error) {
                return Mono.error(new ApiRequestException("SOURCE_NOT_CONFIGURED: QQ音乐Web地址无效"));
            }
            String cookie = credentials.cookie();
            return client.get().uri(target).headers(headers -> {
                        headers.remove("Cookie");
                        headers.remove("Authorization");
                        if (!cookie.isBlank()) headers.set("Cookie", cookie);
                    })
                    .exchangeToMono(response -> response.bodyToMono(JsonNode.class)
                            .switchIfEmpty(Mono.error(failure(response.statusCode().value())))
                            .flatMap(root -> response.statusCode().isError()
                                    ? Mono.error(failure(response.statusCode().value()))
                                    : Mono.just(root)));
        }).timeout(Duration.ofSeconds(20))
                .onErrorMap(error -> error instanceof ApiRequestException ? error
                        : new ApiRequestException(error instanceof java.util.concurrent.TimeoutException
                        ? "UPSTREAM_TIMEOUT: QQ音乐Web请求超时" : "UPSTREAM_UNAVAILABLE: QQ音乐Web请求失败"));
    }

    /** 401/403 表示 Cookie 缺失或失效；429 是上游限流；其余按上游不可用处理。 */
    private ApiRequestException failure(int status) {
        if (status == 401 || status == 403) return SourceAuth.invalid("QQ音乐");
        if (status == 429) return new ApiRequestException("SOURCE_RATE_LIMITED: QQ音乐触发风控（需要登录或安全验证），请稍后重试；配置 Cookie 可显著降低触发概率");
        return new ApiRequestException("UPSTREAM_UNAVAILABLE: QQ音乐Web请求失败");
    }
}
