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

        // 显式设置 Content-Type：produces 注解在 ResponseBodyEmitter 异步路径下不会可靠写出该头。
        // 浏览器/VLC 会嗅探内容自动识别音频，但 VRC 等严格播放器依赖 audio/mpeg 头识别音频流，
        // 缺失会导致其直接拒绝播放（"无法加载音频"）。
        response.setContentType("audio/mpeg");

        // 根因（2026-08-02 实测）：反向代理（nginx/openresty）默认 proxy_buffering on，
        // 会把 app 稳定送出的 ~16KB/s 音频缓冲成 64KB/4s 大块才转给客户端 → VRC 看到 4s 数据空洞
        // → 首连断开重试、~10s 顿卡。X-Accel-Buffering: no 是 nginx 官方标准头，令其对本响应
        // 逐块透传（不缓冲）。直连 app 验证数据本就稳定，此头可根治首连顿卡；对无 nginx 的部署无害。
        response.setHeader("X-Accel-Buffering", "no");

        String remoteAddr = getClientIp(request);

        // 显式长超时（默认 24h）：Tomcat 默认 async 超时仅 30s，且为固定墙钟计时、不因活跃重置，
        // 否则即使正常收听的连接也会被容器掐断。
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(appProperties.getStream().getEmitterTimeoutMs());
        StreamClient client = new StreamClient(new ResponseBodyEmitterStreamSink(emitter), remoteAddr, appProperties.getStream());

        // 先注册终态回调再 addListener：若泵线程因 emitter 初始化竞态提前终止，
        // onCompletion 也能确保 removeListener 执行清理（见 StreamClient#sendWithRetry）
        emitter.onCompletion(() -> liveStreamService.removeListener(client));
        emitter.onTimeout(() -> liveStreamService.removeListener(client));
        emitter.onError(e -> liveStreamService.removeListener(client));

        if (!liveStreamService.addListener(client)) {
            client.close();
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return null;
        }

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
