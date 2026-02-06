package org.thornex.musicparty.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thornex.musicparty.service.stream.LiveStreamService;
import org.thornex.musicparty.service.stream.StreamTokenService;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.io.OutputStream;

@RestController
@RequestMapping("/radio")
@RequiredArgsConstructor
@Slf4j
public class StreamController {

    private final LiveStreamService liveStreamService;
    private final StreamTokenService streamTokenService;

    @GetMapping(value = "/stream", produces = "audio/mpeg")
    public void streamAudio(HttpServletResponse response, @RequestParam(name = "key", required = false) String key) {
        if (!liveStreamService.isEnabled()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }

        if (!streamTokenService.validateToken(key)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        response.setContentType("audio/mpeg");
        response.setHeader("Transfer-Encoding", "chunked");
        response.setHeader("Connection", "keep-alive");
        // 这是一个伪直播，不应该被缓存
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");

        try {
            OutputStream os = response.getOutputStream();
            liveStreamService.addListener(os);
            
            // 保持连接，直到客户端断开或服务器关闭
            // Servlet 线程会被阻塞在这里。对于高并发场景应使用 WebFlux 或 AsyncServlet
            // 但考虑到这是私人/小规模 Music Party，且 VRChat 实例人数有限，阻塞 Servlet 也是可接受的
            // 简单实现：无限睡眠，实际数据写入由 Broadcaster 在另一个线程完成
            // 注意：由于 os 被传递给了 LiveStreamService，写入是在 Service 的线程池中进行的
            // 这里我们需要防止 Controller 方法返回，否则 Response 会被提交/关闭
            
            synchronized (os) {
                os.wait(); 
            }
            
        } catch (IOException | InterruptedException e) {
            log.debug("Stream client disconnected: {}", e.getMessage());
        } finally {
            // 清理工作通常由 Broadcaster 的异常处理完成，但这里也做一个兜底
            // 注意：此时 response.getOutputStream() 可能已经不可用了
        }
    }
}
