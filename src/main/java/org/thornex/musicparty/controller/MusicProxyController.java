package org.thornex.musicparty.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.thornex.musicparty.service.MusicProxyService;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

@Controller
@Slf4j
public class MusicProxyController {

    private final MusicProxyService musicProxyService;

    public MusicProxyController(MusicProxyService musicProxyService) {
        this.musicProxyService = musicProxyService;
    }

    @GetMapping("/proxy/stream")
    public void proxyBilibiliStream(HttpServletRequest request, HttpServletResponse response) {
        MusicProxyService.ProxyState state = musicProxyService.getCurrentState();
        long waitStart = System.currentTimeMillis();

        while (state.status() == MusicProxyService.ProxyStatus.BUFFERING && state.buffer() == null) {
            if (System.currentTimeMillis() - waitStart > 5000) {
                log.error("Proxy initialization timed out.");
                break;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(50); // 每 50ms 检查一次
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            state = musicProxyService.getCurrentState(); // 刷新状态
        }

        // 只有在真正失败或空闲时才返回 503
        if (state.status() == MusicProxyService.ProxyStatus.IDLE ||
                state.status() == MusicProxyService.ProxyStatus.ERROR ||
                state.buffer() == null) {

            // 只有这里才认为是真的没准备好
            sendError(response, HttpStatus.SERVICE_UNAVAILABLE, "Proxy is not ready or failed.");
            return;
        }

        long totalLength = state.totalLength();
        byte[] buffer = state.buffer();
        String rangeHeader = request.getHeader(HttpHeaders.RANGE);

        long start = 0;
        long end = totalLength - 1;

        // 解析 Range 请求头
        if (StringUtils.hasText(rangeHeader) && rangeHeader.startsWith("bytes=")) {
            String[] ranges = rangeHeader.substring("bytes=".length()).split("-");
            try {
                start = Long.parseLong(ranges[0]);
                if (ranges.length > 1) {
                    end = Math.min(Long.parseLong(ranges[1]), totalLength - 1);
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid Range header: {}", rangeHeader);
                sendError(response, HttpStatus.BAD_REQUEST, "Invalid Range header.");
                return;
            }
        }

        if (start > end || start >= totalLength) {
            sendError(response, HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "Requested range not satisfiable.");
            return;
        }

        long contentLength = end - start + 1;

        // 设置响应头
        response.setContentType("audio/mp4");
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setContentLengthLong(contentLength);

        if (StringUtils.hasText(rangeHeader)) {
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader(HttpHeaders.CONTENT_RANGE, String.format("bytes %d-%d/%d", start, end, totalLength));
        } else {
            response.setStatus(HttpServletResponse.SC_OK);
        }

        // 流式写入数据
        try (OutputStream outputStream = response.getOutputStream()) {
            long position = start;
            while (position <= end) {
                // 等待直到需要的数据被下载
                while (position >= state.bytesRead() && state.status() == MusicProxyService.ProxyStatus.BUFFERING) {
                    // 短暂等待，避免CPU空转
                    TimeUnit.MILLISECONDS.sleep(20);
                    state = musicProxyService.getCurrentState(); // 重新获取最新状态

                    // 如果在等待期间代理任务被取消或出错，则中止
                    if (state.status() == MusicProxyService.ProxyStatus.IDLE || state.status() == MusicProxyService.ProxyStatus.ERROR) {
                        log.warn("Proxy task was cancelled or failed while client was waiting for data.");
                        return;
                    }
                }

                // 计算本次可以写入的字节数
                int bytesToWrite = (int) Math.min(1024 * 8, state.bytesRead() - position);
                bytesToWrite = (int) Math.min(bytesToWrite, end - position + 1);

                if (bytesToWrite <= 0) {
                    // 如果下载完成但仍无法读取所需数据，说明请求超出了范围
                    if (state.status() == MusicProxyService.ProxyStatus.COMPLETED) {
                        break;
                    }
                    continue;
                }

                outputStream.write(buffer, (int) position, bytesToWrite);
                position += bytesToWrite;
            }
            outputStream.flush();
        } catch (IOException | InterruptedException e) {
            // 客户端断开连接等情况，属于正常现象，记录为debug级别
            log.debug("IOException during streaming to client (likely client closed connection): {}", e.getMessage());
        }
    }

    private void sendError(HttpServletResponse response, HttpStatus status, String message) {
        try {
            response.sendError(status.value(), message);
        } catch (IOException e) {
            log.error("Failed to send error response to client", e);
        }
    }
}
