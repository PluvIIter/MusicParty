package org.thornex.musicparty.source;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceCredentialRegistryTest {
    private static SourceDescriptor descriptor() {
        return new SourceDescriptor("fake", "Fixture", "music",
                new SourceCapabilities(true, true, true, true, false, false, true, true),
                Set.of(DeliveryMode.DIRECT_URL), List.of());
    }

    @Test
    void savingWaitsForAnInProgressRefresh() throws Exception {
        var sources = mock(SourceRegistry.class);
        when(sources.descriptor("fake")).thenReturn(descriptor());
        var manager = new BlockingCredentialManager();
        var credentials = new SourceCredentialRegistry(sources, List.of(manager));

        var refreshThread = new Thread(() -> credentials.refresh("fake"), "refresh");
        refreshThread.start();
        assertTrue(manager.refreshStarted.await(2, TimeUnit.SECONDS), "刷新应已开始");

        var updateThread = new Thread(() -> credentials.update("fake", "admin-cookie"), "update");
        updateThread.start();
        Thread.sleep(200);
        assertFalse(manager.calls.contains("update"), "保存必须等待正在进行的刷新结束");

        manager.releaseRefresh.countDown();
        refreshThread.join(2000);
        updateThread.join(2000);
        assertEquals(List.of("refresh", "update"), manager.calls);
    }

    private static final class BlockingCredentialManager implements SourceCredentialManager {
        private final CountDownLatch refreshStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRefresh = new CountDownLatch(1);
        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        @Override public String credentialSourceId() { return "fake"; }
        @Override public String credentialType() { return "cookie"; }
        @Override public String credentialLabel() { return "Fake Cookie"; }
        @Override public boolean isCredentialConfigured() { return true; }

        @Override public void updateCredential(String value) {
            calls.add("update");
        }

        @Override public void refreshCredential() {
            refreshStarted.countDown();
            try {
                releaseRefresh.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            calls.add("refresh");
        }
    }
}
