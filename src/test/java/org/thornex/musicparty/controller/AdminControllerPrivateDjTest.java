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
    void enableRejectedWithoutCookie() {
        when(api.isCookieConfigured()).thenReturn(false);
        ResponseEntity<?> resp = controller.updatePrivateDj("pw",
                new AdminPrivateDjUpdateRequest("FM", null, null, null));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("OFF", props.getPrivateDj().getMode()); // 未配置 Cookie 时开启被拒，保持关闭
    }

    @Test
    void enableAcceptedWithCookie() {
        when(api.isCookieConfigured()).thenReturn(true);
        ResponseEntity<?> resp = controller.updatePrivateDj("pw",
                new AdminPrivateDjUpdateRequest("DJ", true, false, false));
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("DJ", props.getPrivateDj().getMode());
        assertTrue(props.getPrivateDj().isFillBlankEnabled());
        verify(djService).invalidate();
        verify(player).broadcastFullPlayerState();
    }

    @Test
    void invalidModeRejected() {
        when(api.isCookieConfigured()).thenReturn(true);
        ResponseEntity<?> resp = controller.updatePrivateDj("pw",
                new AdminPrivateDjUpdateRequest("XYZ", null, null, null));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void switchToOffDoesNotRequireCookie() {
        when(api.isCookieConfigured()).thenReturn(false);
        props.getPrivateDj().setMode("FM");
        ResponseEntity<?> resp = controller.updatePrivateDj("pw",
                new AdminPrivateDjUpdateRequest("OFF", null, null, null));
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("OFF", props.getPrivateDj().getMode()); // 关闭不需 Cookie
    }

    @Test
    void setNeteaseCookieBroadcastsPlayerState() {
        ResponseEntity<?> resp = controller.setCookie("pw", new AdminCookieRequest("netease", "MUSIC_U=abc"));
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(api).updateCookie("MUSIC_U=abc");
        verify(player).broadcastFullPlayerState(); // 刷新控制面板开启门禁
    }
}
