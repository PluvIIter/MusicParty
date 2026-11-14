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
 * QQ 音乐原生 Web API 客户端.
 *
 * <p>与网易云源一样只做三件事：拼 URI、按配置转发 Cookie、把上游 JSON 解出来。
 * 不解析响应业务码，不重新定义上游权限策略，错误信息也不回显上游原文。</p>
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

    public Mono<JsonNode> get(String path, Map<String, ?> query) {
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
                            .switchIfEmpty(Mono.error(unavailable()))
                            .flatMap(root -> {
                                if (response.statusCode().isError()) return Mono.error(unavailable());
                                JsonNode data = root.hasNonNull("data") ? root.get("data") : root;
                                return Mono.just(data);
                            }));
        }).timeout(Duration.ofSeconds(20))
                .onErrorMap(error -> error instanceof ApiRequestException ? error
                        : new ApiRequestException(error instanceof java.util.concurrent.TimeoutException
                        ? "UPSTREAM_TIMEOUT: QQ音乐Web请求超时" : "UPSTREAM_UNAVAILABLE: QQ音乐Web请求失败"));
    }

    private ApiRequestException unavailable() {
        return new ApiRequestException("UPSTREAM_UNAVAILABLE: QQ音乐Web请求失败");
    }
}
