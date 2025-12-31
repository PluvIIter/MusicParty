package org.thornex.musicparty.service;

import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class MusicProxyService {

    private final WebClient webClient;
    private final ExecutorService downloadExecutor = Executors.newCachedThreadPool();

    // --- 全局代理状态 ---
    // volatile确保多线程之间的可见性
    @Getter
    private volatile ProxyState currentState = new ProxyState(ProxyStatus.IDLE, null, 0, 0, null);
    private AtomicReference<Disposable> currentDownloadTask = new AtomicReference<>(null);

    public MusicProxyService(WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * 启动一个新的代理任务。这将取消任何正在进行的旧任务。
     * @param targetUrl 要代理的Bilibili音频URL
     */
    public synchronized Mono<Void> startProxy(String targetUrl) {
        if (targetUrl == null || !targetUrl.startsWith("http")) {
            log.error("Invalid proxy target URL: {}", targetUrl);
            return Mono.error(new IllegalArgumentException("Invalid URL"));
        }
        log.info("Request to start proxy for URL: {}", targetUrl);
        cancelCurrentProxy(); // 停止并清理上一个任务

        CompletableFuture<Void> readyFuture = new CompletableFuture<>();

        // 初始化新任务的状态
        this.currentState = new ProxyState(ProxyStatus.BUFFERING, targetUrl, 0, 0, null);

        downloadExecutor.submit(() -> {
            log.info("Starting download for: {}", targetUrl);
            Disposable disposable = webClient.get()
                    .uri(targetUrl)
                    .header("Referer", "https://www.bilibili.com/")
                    .exchangeToMono(response -> {
                        long totalLength = response.headers().contentLength().orElse(-1);
                        if (totalLength > 50 * 1024 * 1024) {
                            log.error("File too large for memory buffer: {} bytes", totalLength);
                            return Mono.error(new RuntimeException("File too large"));
                        }
                        if (totalLength <= 0) {
                            // 失败时通知 Future
                            readyFuture.completeExceptionally(new RuntimeException("Invalid content length"));
                            log.error("Invalid content length: {}", totalLength);
                            currentState = currentState.withStatus(ProxyStatus.ERROR);
                            return Mono.error(new RuntimeException("Invalid content length for proxy target."));
                        }

                        // 分配缓冲区并更新状态
                        byte[] buffer = new byte[(int) totalLength];
                        currentState = currentState.withBuffer(buffer).withTotalLength(totalLength);
                        log.info("Allocated buffer of size {} bytes for proxy.", totalLength);

                        readyFuture.complete(null);

                        // 使用 a non-blocking subscriber on a separate thread to handle the stream
                        return response.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class)
                                .publishOn(Schedulers.boundedElastic())
                                .doOnNext(dataBuffer -> {
                                    try {
                                        int readableBytes = dataBuffer.readableByteCount();
                                        long currentBytesRead = currentState.bytesRead();
                                        dataBuffer.read(buffer, (int) currentBytesRead, readableBytes);
                                        currentState = currentState.withBytesRead(currentBytesRead + readableBytes);
                                    } finally {
                                        DataBufferUtils.release(dataBuffer);
                                    }
                                })
                                .doOnComplete(() -> {
                                    log.info("Proxy download completed for URL: {}", targetUrl);
                                    currentState = currentState.withStatus(ProxyStatus.COMPLETED);
                                })
                                .doOnError(error -> {
                                    log.error("Error during proxy download", error);
                                    currentState = currentState.withStatus(ProxyStatus.ERROR);
                                })
                                .then();
                    })
                    .subscribe();

            currentDownloadTask.set(disposable);
        });

        return Mono.fromFuture(readyFuture);
    }

    /**
     * 取消并重置当前的代理任务
     */
    public synchronized void cancelCurrentProxy() {
        Disposable oldTask = currentDownloadTask.getAndSet(null);
        if (oldTask != null && !oldTask.isDisposed()) {
            log.info("Cancelling previous proxy download task.");
            oldTask.dispose();
        }
        this.currentState = new ProxyState(ProxyStatus.IDLE, null, 0, 0, null);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down MusicProxyService executor.");
        cancelCurrentProxy();
        downloadExecutor.shutdown();
    }

    // --- 内部状态类 ---
    public enum ProxyStatus { IDLE, BUFFERING, COMPLETED, ERROR }

    /**
     * 使用Record来创建一个不可变的状态对象，便于线程安全地更新
     */
    public record ProxyState(
            ProxyStatus status,
            String targetUrl,
            long totalLength,
            long bytesRead,
            byte[] buffer
    ) {
        public ProxyState withStatus(ProxyStatus newStatus) {
            return new ProxyState(newStatus, this.targetUrl, this.totalLength, this.bytesRead, this.buffer);
        }
        public ProxyState withBuffer(byte[] newBuffer) {
            return new ProxyState(this.status, this.targetUrl, this.totalLength, this.bytesRead, newBuffer);
        }
        public ProxyState withTotalLength(long newTotalLength) {
            return new ProxyState(this.status, this.targetUrl, newTotalLength, this.bytesRead, this.buffer);
        }
        public ProxyState withBytesRead(long newBytesRead) {
            return new ProxyState(this.status, this.targetUrl, this.totalLength, newBytesRead, this.buffer);
        }
    }
}