package org.thornex.musicparty.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.service.stream.LiveStreamService;
import org.thornex.musicparty.service.stream.ResponseBodyEmitterStreamSink;
import org.thornex.musicparty.service.stream.StreamClient;
import org.thornex.musicparty.service.stream.StreamTokenService;

@RestController
@RequestMapping("/radio")
@RequiredArgsConstructor
@Slf4j
public class StreamController {

    private final LiveStreamService liveStreamService;
    private final StreamTokenService streamTokenService;
    private final AppProperties appProperties;

    @GetMapping(value = "/stream", produces = "audio/mpeg")
    public ResponseBodyEmitter streamAudio(HttpServletRequest request, HttpServletResponse response,
                                           @RequestParam(name = "key", required = false) String key) {
        if (!liveStreamService.isEnabled()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return null;
        }

        if (!streamTokenService.validateToken(key)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return null;
        }

        // 伪直播，不应该被缓存
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");

        String remoteAddr = getClientIp(request);

        // 显式长超时（默认 24h）：Tomcat 默认 async 超时仅 30s，且为固定墙钟计时、不因活跃重置，
        // 否则即使正常收听的连接也会被容器掐断。
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(appProperties.getStream().getEmitterTimeoutMs());
        StreamClient client = new StreamClient(new ResponseBodyEmitterStreamSink(emitter), remoteAddr, appProperties.getStream());

        if (!liveStreamService.addListener(client)) {
            client.close();
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return null;
        }

        // 连接生命周期由容器管理：所有终态（断开/超时/错误）都汇聚到幂等的 removeListener
        emitter.onCompletion(() -> liveStreamService.removeListener(client));
        emitter.onTimeout(() -> liveStreamService.removeListener(client));
        emitter.onError(e -> liveStreamService.removeListener(client));

        return emitter;
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 处理多级代理情况，取第一个非 unknown 的 IP
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
