package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.source.SourceCredentialRegistry;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SourceCredentialRefreshSchedulerTest {
    @Test void scheduledTriggerReturnsBeforeTheRefreshFinishes() throws Exception {
        var registry = mock(SourceCredentialRegistry.class);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await(2, TimeUnit.SECONDS);
            return null;
        }).when(registry).refreshIfNeeded(anyString());

        var scheduler = new SourceCredentialRefreshScheduler(registry);
        long begin = System.nanoTime();
        scheduler.refreshConfiguredCredentials();
        long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;

        assertTrue(elapsedMillis < 200, "调度线程不应等待上游刷新");
        assertTrue(started.await(2, TimeUnit.SECONDS));

        scheduler.refreshConfiguredCredentials();
        release.countDown();
        verify(registry, org.mockito.Mockito.timeout(2000).times(2)).refreshIfNeeded(anyString());
        scheduler.shutdown();
    }
}
