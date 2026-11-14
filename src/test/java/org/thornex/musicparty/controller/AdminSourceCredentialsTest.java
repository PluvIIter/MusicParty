package org.thornex.musicparty.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.AdminCookieRequest;
import org.thornex.musicparty.service.*;
import org.thornex.musicparty.service.api.IMusicApiService;
import org.thornex.musicparty.service.stream.LiveStreamService;
import org.thornex.musicparty.source.*;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminSourceCredentialsTest {
    private final AppProperties props = new AppProperties();
    private final MusicPlayerService player = mock(MusicPlayerService.class);
    private final PrivateDjService dj = mock(PrivateDjService.class);
    private final SourceCredentialManager credentials = mock(SourceCredentialManager.class);
    private final SourceRegistry sources;
    private final AdminController controller;

    AdminSourceCredentialsTest() {
        props.setAdminPassword("admin-only");
        var source = mock(IMusicApiService.class);
        when(source.getPlatformName()).thenReturn("qqmusic");
        when(source.defaultDeliveryMode()).thenReturn(DeliveryMode.SERVER_DOWNLOAD);
        when(source.defaultDisabled()).thenReturn(true);
        when(source.descriptor()).thenReturn(new SourceDescriptor("qqmusic", "QQ音乐", "music",
                new SourceCapabilities(true, true, true, true, true, true, true, true),
                Set.of(DeliveryMode.SERVER_DOWNLOAD), List.of(new CollectionCategory("playlists", "歌单", "playlist", "CONTAINER"))));
        sources = new SourceRegistry(List.of(source), props);
        when(credentials.credentialSourceId()).thenReturn("qqmusic");
        when(credentials.credentialType()).thenReturn("qqmusic-credential");
        when(credentials.credentialLabel()).thenReturn("QQ音乐登录凭据（JSON）");
        when(credentials.isCredentialConfigured()).thenReturn(true);
        controller = new AdminController(player, mock(ChatService.class), props, mock(AuthController.class),
                new SourceCredentialRegistry(sources, List.of(credentials)), mock(LiveStreamService.class), dj,
                mock(QueuePersistenceService.class));
    }

    @Test
    void disabledSourceManagementIsAdminOnlyAndNeverReturnsSecrets() throws Exception {
        assertEquals(HttpStatus.FORBIDDEN, controller.sources("wrong").getStatusCode());
        String json = new ObjectMapper().writeValueAsString(controller.sources("admin-only").getBody());
        assertTrue(json.contains("qqmusic"));
        assertTrue(json.contains("\"enabled\":false"));
        assertTrue(json.contains("\"configured\":true"));
        assertFalse(json.contains("musickey"));
        assertFalse(json.contains("admin-only"));
        assertTrue(((List<?>) sources.catalog().get("sources")).isEmpty());
    }

    @Test
    void authorizedUpdateWorksWhileDisabledAndInvalidatesRecommendations() {
        var request = new AdminCookieRequest("qqmusic", "sensitive-value");
        assertEquals(HttpStatus.FORBIDDEN, controller.setCookie("wrong", request).getStatusCode());
        verify(credentials, never()).updateCredential(anyString());
        assertEquals(HttpStatus.OK, controller.setCookie("admin-only", request).getStatusCode());
        verify(credentials).updateCredential("sensitive-value");
        verify(dj).invalidate();
        verify(player).broadcastFullPlayerState();
        assertFalse(sources.isEnabled("qqmusic"));
    }

    @Test
    void malformedUnknownAndOversizedCredentialsDoNotLeakOrBroadcast() throws Exception {
        doThrow(new IllegalArgumentException("reflected-sensitive-value")).when(credentials).updateCredential("bad");
        var bad = controller.setCookie("admin-only", new AdminCookieRequest("qqmusic", "bad"));
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
        assertFalse(new ObjectMapper().writeValueAsString(bad.getBody()).contains("sensitive"));
        assertEquals(HttpStatus.BAD_REQUEST, controller.setCookie("admin-only", new AdminCookieRequest("other", "secret")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.setCookie("admin-only", new AdminCookieRequest(null, "secret")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.setCookie("admin-only", new AdminCookieRequest("qqmusic", "x".repeat(32769))).getStatusCode());
        verifyNoInteractions(player, dj);
    }

    @Test
    void clearingCredentialDoesNotEnableSource() {
        assertEquals(HttpStatus.OK, controller.setCookie("admin-only", new AdminCookieRequest("qqmusic", null)).getStatusCode());
        verify(credentials).updateCredential("");
        assertFalse(sources.isEnabled("qqmusic"));
    }
}
