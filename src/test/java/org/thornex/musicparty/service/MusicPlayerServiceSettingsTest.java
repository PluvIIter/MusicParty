package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.PlayerState;
import org.thornex.musicparty.dto.SettingsSnapshot;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicPlayerServiceSettingsTest {

    private MusicPlayerService build() {
        return new MusicPlayerService(
                List.of(),
                mock(org.thornex.musicparty.service.UserService.class),
                mock(org.thornex.musicparty.service.LocalCacheService.class),
                mock(org.thornex.musicparty.service.stream.LiveStreamService.class),
                mock(MusicQueueManager.class),
                mock(ApplicationEventPublisher.class),
                new AppProperties(),
                mock(NeteaseMusicApiService.class),
                mock(PrivateDjService.class)
        );
    }

    @Test
    void applyPlayerSettingsRestoresAllFields() {
        MusicPlayerService service = build();

        service.applyPlayerSettings(new SettingsSnapshot.PlayerSettings(
                "SHUFFLE", true, true, true, 0.75, 20, true, true, true));

        PlayerState state = service.getCurrentPlayerState();
        assertEquals("SHUFFLE", state.playMode());
        assertTrue(state.isShuffle());              // isShuffle 派生同步
        assertTrue(state.isFairShuffle());
        assertTrue(state.allowOfflineShuffle());
        assertTrue(state.isVoteSkipEnabled());
        assertEquals(0.75, state.voteSkipThreshold());
        assertEquals(20, state.voteSkipWaitTime());
        assertTrue(state.isPauseLocked());
        assertTrue(state.isSkipLocked());
        assertTrue(state.isPlayModeLocked());
    }

    @Test
    void applyPlayerSettingsSyncsIsShuffleForNonShuffleModes() {
        MusicPlayerService service = build();
        service.applyPlayerSettings(new SettingsSnapshot.PlayerSettings(
                "REPEAT_ONE", null, null, null, null, null, null, null, null));

        PlayerState state = service.getCurrentPlayerState();
        assertEquals("REPEAT_ONE", state.playMode());
        assertFalse(state.isShuffle());
    }

    @Test
    void applyPlayerSettingsIgnoresInvalidPlayMode() {
        MusicPlayerService service = build();
        // 先切到非默认模式，再喂非法值 —— 钉住"失败时保留当前模式、不回退默认"
        service.applyPlayerSettings(new SettingsSnapshot.PlayerSettings(
                "SHUFFLE", null, null, null, null, null, null, null, null));
        service.applyPlayerSettings(new SettingsSnapshot.PlayerSettings(
                "BOGUS", null, null, null, null, null, null, null, null));

        PlayerState state = service.getCurrentPlayerState();
        assertEquals("SHUFFLE", state.playMode()); // 非法值被忽略，保留 SHUFFLE
        assertTrue(state.isShuffle());
    }

    @Test
    void getPlayerSettingsCapturesCurrentState() {
        MusicPlayerService service = build();
        service.applyPlayerSettings(new SettingsSnapshot.PlayerSettings(
                "SHUFFLE", false, true, true, 0.6, 10, true, false, true));

        SettingsSnapshot.PlayerSettings captured = service.getPlayerSettings();

        assertEquals("SHUFFLE", captured.playMode());
        assertFalse(captured.fairShuffle());
        assertTrue(captured.allowOfflineShuffle());
        assertTrue(captured.voteSkipEnabled());
        assertEquals(0.6, captured.voteSkipThreshold());
        assertEquals(10, captured.voteSkipWaitTime());
        assertTrue(captured.pauseLocked());
        assertFalse(captured.skipLocked());
        assertTrue(captured.playModeLocked());
    }

    @Test
    void getPlayerSettingsReflectsConstructionDefaults() {
        MusicPlayerService service = build();

        SettingsSnapshot.PlayerSettings captured = service.getPlayerSettings();

        assertEquals("SEQUENTIAL", captured.playMode());
        assertTrue(captured.fairShuffle());         // 构造默认 true
        assertFalse(captured.allowOfflineShuffle());
        assertFalse(captured.voteSkipEnabled());
        assertEquals(0.5, captured.voteSkipThreshold());
        assertEquals(15, captured.voteSkipWaitTime());
        assertFalse(captured.pauseLocked());
        assertFalse(captured.skipLocked());
        assertFalse(captured.playModeLocked());
    }
}
