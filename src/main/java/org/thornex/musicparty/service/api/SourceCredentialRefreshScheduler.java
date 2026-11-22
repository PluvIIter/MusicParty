package org.thornex.musicparty.service.api;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.source.SourceCredentialRegistry;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 刷新凭据会阻塞等待上游，不能跑在 Spring 默认的单线程调度器上，否则播放推进等定时任务会被拖住。
 */
@Slf4j
@Service
public class SourceCredentialRefreshScheduler {
    private final SourceCredentialRegistry credentials;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "source-credential-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean refreshing = new AtomicBoolean();

    public SourceCredentialRefreshScheduler(SourceCredentialRegistry credentials) {
        this.credentials = credentials;
    }

    @Scheduled(fixedDelayString = "${app.music-api.credential-refresh-interval-ms:172800000}",
            initialDelayString = "${app.music-api.credential-refresh-initial-delay-ms:60000}")
    public void refreshConfiguredCredentials() {
        if (!refreshing.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                for (String source : new String[]{"netease", "qqmusic"}) {
                    try {
                        credentials.refresh(source);
                    } catch (RuntimeException error) {
                        log.warn("Automatic {} credential refresh failed", source);
                    }
                }
            } finally {
                refreshing.set(false);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
