package org.thornex.musicparty.source;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SourceCredentialRegistryTest {
    private static final String SOURCE = "qqmusic";

    private SourceRegistry sources;
    private SourceCredentialManager manager;
    private SourceCredentialRegistry registry;

    @BeforeEach
    void setUp() {
        sources = mock(SourceRegistry.class);
        manager = mock(SourceCredentialManager.class);
        when(manager.credentialSourceId()).thenReturn(SOURCE);
        when(manager.credentialType()).thenReturn("cookie");
        when(manager.credentialLabel()).thenReturn("QQ音乐 Cookie");
        when(manager.isCredentialConfigured()).thenReturn(true);
        when(sources.isEnabled(SOURCE)).thenReturn(true);
        registry = new SourceCredentialRegistry(sources, List.of(manager));
    }

    @Test
    void disabledSourceIsNotRefreshedAutomatically() {
        when(sources.isEnabled(SOURCE)).thenReturn(false);

        registry.refreshEnabled(SOURCE);

        verify(manager, never()).refreshCredential();
    }

    @Test
    void enabledSourceIsRefreshedAutomatically() {
        registry.refreshEnabled(SOURCE);

        verify(manager).refreshCredential();
    }

    @Test
    void unconfiguredSourceIsNotRefreshedAutomatically() {
        when(manager.isCredentialConfigured()).thenReturn(false);

        registry.refreshEnabled(SOURCE);

        verify(manager, never()).refreshCredential();
    }

    @Test
    void savingCredentialsWaitsForAnInFlightRefresh() throws Exception {
        var refreshStarted = new CountDownLatch(1);
        var releaseRefresh = new CountDownLatch(1);
        doAnswer(invocation -> {
            refreshStarted.countDown();
            releaseRefresh.await(2, TimeUnit.SECONDS);
            return null;
        }).when(manager).refreshCredential();

        var refreshing = new Thread(() -> registry.refreshEnabled(SOURCE));
        refreshing.start();
        assertTrue(refreshStarted.await(2, TimeUnit.SECONDS));

        var saved = new AtomicBoolean();
        var saving = new Thread(() -> {
            registry.update(SOURCE, "fresh-cookie");
            saved.set(true);
        });
        saving.start();
        saving.join(200);

        assertFalse(saved.get(), "保存应等待正在进行的刷新，避免被刷新结果覆盖");

        releaseRefresh.countDown();
        refreshing.join(2000);
        saving.join(2000);
        assertTrue(saved.get());

        var order = inOrder(manager);
        order.verify(manager).refreshCredential();
        order.verify(manager).updateCredential("fresh-cookie");
    }
}
