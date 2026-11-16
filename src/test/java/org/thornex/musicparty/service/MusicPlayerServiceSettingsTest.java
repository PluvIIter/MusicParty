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

    @Test
    void retentionAdminUpdatesArePartialAndAppearInSharedState() throws Exception {
        MusicPlayerService service = build();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertFalse(service.getCurrentPlayerState().config().retainPlaylist());
        service.updateConfig(mapper.readValue("{\"retainPlaylist\":true}", org.thornex.musicparty.dto.AdminConfigUpdateRequest.class));
        assertTrue(service.getCurrentPlayerState().config().retainPlaylist());
        service.updateConfig(mapper.readValue("{\"maxUserSongs\":55}", org.thornex.musicparty.dto.AdminConfigUpdateRequest.class));
        assertTrue(service.getCurrentPlayerState().config().retainPlaylist());
        assertEquals(55, service.getCurrentPlayerState().config().maxUserSongs());
        service.updateConfig(mapper.readValue("{\"retainPlaylist\":false}", org.thornex.musicparty.dto.AdminConfigUpdateRequest.class));
        assertFalse(service.getCurrentPlayerState().config().retainPlaylist());
    }

    private MusicPlayerService build() {
        return PlayerTestFactory.create(
                List.of(),
                mock(org.thornex.musicparty.service.UserService.class),
                mock(org.thornex.musicparty.service.stream.LiveStreamService.class),
                mock(MusicQueueManager.class),
                mock(ApplicationEventPublisher.class),
                new AppProperties(),
                mock(PrivateDjService.class)
        );
    }

    @Test
    void applyPlayerSettingsRestoresAllFields() {
        MusicPlayerService service = build();

        service.applyPlayerSettings(new SettingsSnapshot.PlayerSettings(
                "SHUFFLE", true, true, 0.75, 20, true, true, true));

        PlayerState state = service.getCurrentPlayerState();
        assertEquals("SHUFFLE", state.playMode());
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
                "REPEAT_ONE", null, null, null, null, null, null, null));

        PlayerState state = service.getCurrentPlayerState();
        assertEquals("REPEAT_ONE", state.playMode());
    }

    @Test
    void applyPlayerSettingsIgnoresInvalidPlayMode() {
        MusicPlayerService service = build();
        // 先切到非默认模式，再喂非法值 —— 钉住"失败时保留当前模式、不回退默认"
        service.applyPlayerSettings(new SettingsSnapshot.PlayerSettings(
                "SHUFFLE", null, null, null, null, null, null, null));
        service.applyPlayerSettings(new SettingsSnapshot.PlayerSettings(
                "BOGUS", null, null, null, null, null, null, null));

        PlayerState state = service.getCurrentPlayerState();
        assertEquals("SHUFFLE", state.playMode()); // 非法值被忽略，保留 SHUFFLE
    }

    @Test
    void getPlayerSettingsCapturesCurrentState() {
        MusicPlayerService service = build();
        service.applyPlayerSettings(new SettingsSnapshot.PlayerSettings(
                "SHUFFLE", true, true, 0.6, 10, true, false, true));

        SettingsSnapshot.PlayerSettings captured = service.getPlayerSettings();

        assertEquals("SHUFFLE", captured.playMode());
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
        assertFalse(captured.allowOfflineShuffle());
        assertFalse(captured.voteSkipEnabled());
        assertEquals(0.5, captured.voteSkipThreshold());
        assertEquals(15, captured.voteSkipWaitTime());
        assertFalse(captured.pauseLocked());
        assertFalse(captured.skipLocked());
        assertFalse(captured.playModeLocked());
    }

    /** 拖动进度条开关：默认关闭 → 打开后出现在共享状态里 → 可再关闭。 */
    @Test
    void seekPermissionTogglesAndAppearsInSharedState() throws Exception {
        MusicPlayerService service = build();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertFalse(service.getCurrentPlayerState().config().allowSeek());

        service.updateConfig(
                mapper.readValue("{\"allowSeek\":true}", org.thornex.musicparty.dto.AdminConfigUpdateRequest.class));
        assertTrue(service.getCurrentPlayerState().config().allowSeek());

        service.updateConfig(
                mapper.readValue("{\"allowSeek\":false}", org.thornex.musicparty.dto.AdminConfigUpdateRequest.class));
        assertFalse(service.getCurrentPlayerState().config().allowSeek());
    }

    /** 没有曲目时 seek 是安全的 no-op（不能抛异常，也不能改动状态）。 */
    @Test
    void seekWithoutTrackIsNoOp() {
        MusicPlayerService service = build();
        assertDoesNotThrow(() -> service.seekTo("session", 5000, "no-track"));
        assertEquals(0L, service.getCurrentPlayerState().seekNonce());
    }

    /** 目标位置裁剪：负数归零、超出留在末尾前 500ms、未知时长只保证非负。 */
    @Test
    void seekPositionIsClamped() {
        assertEquals(0, MusicPlayerService.clampSeek(-1000, 180000));
        assertEquals(0, MusicPlayerService.clampSeek(0, 180000));
        assertEquals(60000, MusicPlayerService.clampSeek(60000, 180000));
        assertEquals(179500, MusicPlayerService.clampSeek(999999, 180000));
        assertEquals(0, MusicPlayerService.clampSeek(300, 200));
        assertEquals(999999, MusicPlayerService.clampSeek(999999, 0));
    }
}
