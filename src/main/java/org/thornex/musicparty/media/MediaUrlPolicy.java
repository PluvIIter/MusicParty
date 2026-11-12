package org.thornex.musicparty.media;

import org.thornex.musicparty.exception.ApiRequestException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.InetAddress;
import java.net.URI;
import java.util.Map;

public final class MediaUrlPolicy {
    private MediaUrlPolicy() {}

    public static Mono<ResolvedMedia> validate(ResolvedMedia media) {
        return Mono.fromCallable(() -> {
            validateUrl(media.url());
            validateHeaders(media.headers());
            return media;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public static void validateUrl(String value) {
        try {
            URI uri = URI.create(value);
            // 端口不做白名单：B 站 DASH 音频走 mcdn.bilivideo.cn:8082、备份 edge CDN 走 :4483，
            // 限制 80/443 会把这些合法媒体地址全部拒掉。SSRF 防护依赖下面的地址段校验。
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                byte[] bytes = address.getAddress();
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()
                        || (bytes.length == 4 && ((bytes[0] & 255) == 0 || (bytes[0] & 255) >= 224
                        || ((bytes[0] & 255) == 100 && (bytes[1] & 255) >= 64 && (bytes[1] & 255) <= 127)))
                        || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc)) {
                    throw new IllegalArgumentException();
                }
            }
        } catch (Exception error) {
            throw new ApiRequestException("媒体地址不符合安全策略");
        }
    }

    public static void validateHeaders(Map<String, String> headers) {
        headers.forEach((name, value) -> {
            if (!name.matches("[A-Za-z0-9-]+") || value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                    || name.equalsIgnoreCase("Host") || name.equalsIgnoreCase("Connection")) {
                throw new ApiRequestException("媒体请求头不符合安全策略");
            }
        });
    }
}
