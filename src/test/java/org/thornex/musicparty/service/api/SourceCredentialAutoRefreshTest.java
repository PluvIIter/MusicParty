package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.source.SourceCredentialRegistry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SourceCredentialAutoRefreshTest {
    @Test
    void refreshesAtMostOncePerCooldown() {
        var registry = mock(SourceCredentialRegistry.class);
        var refresh = new SourceCredentialAutoRefresh(registry);

        assertTrue(refresh.refresh("qqmusic"));
        assertFalse(refresh.refresh("qqmusic"));
        verify(registry, times(1)).refresh("qqmusic");
    }

    @Test
    void reportsUpstreamFailureWithoutThrowing() {
        var registry = mock(SourceCredentialRegistry.class);
        doThrow(new ApiRequestException("SOURCE_AUTH_REQUIRED: 刷新失败")).when(registry).refresh("netease");
        var refresh = new SourceCredentialAutoRefresh(registry);

        assertFalse(refresh.refresh("netease"));
        verify(registry).refresh("netease");
    }

    @Test
    void onlyTreatsCredentialErrorsAsAuthFailures() {
        assertTrue(SourceCredentialAutoRefresh.isAuthFailure(new ApiRequestException("SOURCE_AUTH_REQUIRED: x")));
        assertTrue(SourceCredentialAutoRefresh.isAuthFailure(new ApiRequestException("RECOMMENDATION_AUTH_REQUIRED: x")));
        assertFalse(SourceCredentialAutoRefresh.isAuthFailure(new ApiRequestException("MEDIA_FORBIDDEN: x")));
        assertFalse(SourceCredentialAutoRefresh.isAuthFailure(new IllegalStateException("boom")));
    }
}
