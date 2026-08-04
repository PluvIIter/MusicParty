package org.thornex.musicparty.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.AdminCookieRequest;
import org.thornex.musicparty.dto.AdminPrivateDjUpdateRequest;
import org.thornex.musicparty.service.ChatService;
import org.thornex.musicparty.service.MusicPlayerService;
import org.thornex.musicparty.service.PrivateDjService;
import org.thornex.musicparty.service.api.BilibiliMusicApiService;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import org.thornex.musicparty.service.stream.LiveStreamService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminControllerPrivateDjTest {

    private AppProperties props;
    private MusicPlayerService player;
    private NeteaseMusicApiService api;
    private PrivateDjService djService;
    private AdminController controller;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.setAdminPassword("pw");
        player = mock(MusicPlayerService.class);
        api = mock(NeteaseMusicApiService.class);
        djService = mock(PrivateDjService.class);
        controller = new AdminController(player,
                mock(ChatService.class), props, mock(AuthController.class),
                api, mock(BilibiliMusicApiService.class), mock(LiveStreamService.class), djService);
    }

    @Test
    void masterEnableRejectedWithoutCookie() {
        when(api.isCookieConfigured()).thenReturn(false);
        ResponseEntity<?> resp = controller.updatePrivateDj("pw",
                new AdminPrivateDjUpdateRequest(true, null, null, null, null));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertFalse(props.getPrivateDj().isMasterEnabled());
    }

    @Test
    void masterEnableAcceptedWithCookie() {
        when(api.isCookieConfigured()).thenReturn(true);
        ResponseEntity<?> resp = controller.updatePrivateDj("pw",
                new AdminPrivateDjUpdateRequest(true, "DJ", true, false, false));
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(props.getPrivateDj().isMasterEnabled());
        assertEquals("DJ", props.getPrivateDj().getMode());
        assertTrue(props.getPrivateDj().isFillBlankEnabled());
        verify(djService).invalidate();
        verify(player).broadcastFullPlayerState();
    }

    @Test
    void invalidModeRejected() {
        when(api.isCookieConfigured()).thenReturn(true);
        ResponseEntity<?> resp = controller.updatePrivateDj("pw",
                new AdminPrivateDjUpdateRequest(null, "XYZ", null, null, null));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void setNeteaseCookieBroadcastsPlayerState() {
        ResponseEntity<?> resp = controller.setCookie("pw", new AdminCookieRequest("netease", "MUSIC_U=abc"));
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(api).updateCookie("MUSIC_U=abc");
        verify(player).broadcastFullPlayerState(); // 刷新控制面板总开关门禁
    }
}
