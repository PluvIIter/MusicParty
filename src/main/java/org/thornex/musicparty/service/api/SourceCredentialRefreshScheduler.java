package org.thornex.musicparty.service.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.source.SourceCredentialRegistry;

@Slf4j
@Service
public class SourceCredentialRefreshScheduler {
    private final SourceCredentialRegistry credentials;

    public SourceCredentialRefreshScheduler(SourceCredentialRegistry credentials) {
        this.credentials = credentials;
    }

    @Scheduled(fixedDelayString = "${app.music-api.credential-refresh-interval-ms:1800000}",
            initialDelayString = "${app.music-api.credential-refresh-initial-delay-ms:300000}")
    public void refreshConfiguredCredentials() {
        for (String source : new String[]{"netease", "qqmusic"}) {
            try {
                credentials.refreshIfNeeded(source);
            } catch (RuntimeException error) {
                log.warn("Automatic {} credential refresh failed", source);
            }
        }
    }
}
