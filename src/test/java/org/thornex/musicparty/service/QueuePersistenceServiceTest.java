package org.thornex.musicparty.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.controller.AuthController;
import org.thornex.musicparty.dto.SettingsSnapshot;
import org.thornex.musicparty.service.stream.LiveStreamService;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueuePersistenceServiceTest {

    // 生产 ObjectMapper 关闭 FAIL_ON_UNKNOWN_PROPERTIES，测试显式对齐
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private QueuePersistenceService build(MusicQueueManager qm, ChatService chat,
            AppProperties props, MusicPlayerService player, AuthController auth, LiveStreamService stream) {
        return new QueuePersistenceService(qm, chat, props, mapper, player, auth, stream);
    }

    @Test
    void saveDataWritesSettingsSnapshot() throws Exception {
        File tmp = File.createTempFile("queue", ".json");
        tmp.deleteOnExit();
        AppProperties props = new AppProperties();
        props.getQueue().setPersistenceFile(tmp.getAbsolutePath());
        props.getPrivateDj().setMode("DJ");
        props.getPrivateDj().setFillBlankEnabled(true);
        props.getNetease().setEnabled(true);
        props.getBilibili().setEnabled(false);
        props.getQueue().setMaxSize(500);

        MusicQueueManager qm = mock(MusicQueueManager.class);
        when(qm.getQueueSnapshot()).thenReturn(List.of());
        when(qm.getHistorySnapshot()).thenReturn(List.of());
        ChatService chat = mock(ChatService.class);
        when(chat.getHistoryFull()).thenReturn(List.of());
        MusicPlayerService player = mock(MusicPlayerService.class);
        when(player.getPlayerSettings()).thenReturn(new SettingsSnapshot.PlayerSettings(
                "SHUFFLE", true, true, true, 0.75, 20, true, true, true));
        AuthController auth = mock(AuthController.class);
        when(auth.getRawPassword()).thenReturn("room123");
        LiveStreamService stream = mock(LiveStreamService.class);
        when(stream.isEnabled()).thenReturn(true);

        build(qm, chat, props, player, auth, stream).saveData();

        String json = Files.readString(tmp.toPath());
        JsonNode root = mapper.readTree(json);
        assertTrue(root.has("settings"), "saved json must contain settings section");
        SettingsSnapshot snap = mapper.treeToValue(root.get("settings"), SettingsSnapshot.class);

        assertEquals("SHUFFLE", snap.player().playMode());
        assertTrue(snap.player().voteSkipEnabled());
        assertEquals("room123", snap.roomPassword());
        assertTrue(snap.streamEnabled());
        assertEquals("DJ", snap.privateDj().mode());
        assertTrue(snap.privateDj().fillBlankEnabled());
        assertEquals(500, snap.systemConfig().maxQueueSize());
        assertTrue(snap.systemConfig().neteaseEnabled());
        assertFalse(snap.systemConfig().bilibiliEnabled());
    }

    @Test
    void saveDataWritesNullRoomPasswordWhenUninitialized() throws Exception {
        File tmp = File.createTempFile("queue", ".json");
        tmp.deleteOnExit();
        AppProperties props = new AppProperties();
        props.getQueue().setPersistenceFile(tmp.getAbsolutePath());

        MusicQueueManager qm = mock(MusicQueueManager.class);
        when(qm.getQueueSnapshot()).thenReturn(List.of());
        when(qm.getHistorySnapshot()).thenReturn(List.of());
        ChatService chat = mock(ChatService.class);
        when(chat.getHistoryFull()).thenReturn(List.of());
        MusicPlayerService player = mock(MusicPlayerService.class);
        when(player.getPlayerSettings()).thenReturn(new SettingsSnapshot.PlayerSettings(
                "SEQUENTIAL", null, null, null, null, null, null, null, null));
        AuthController auth = mock(AuthController.class);
        when(auth.getRawPassword()).thenReturn(null);
        LiveStreamService stream = mock(LiveStreamService.class);
        when(stream.isEnabled()).thenReturn(false);

        build(qm, chat, props, player, auth, stream).saveData();

        SettingsSnapshot snap = mapper.treeToValue(
                mapper.readTree(Files.readString(tmp.toPath())).get("settings"), SettingsSnapshot.class);
        assertNull(snap.roomPassword());
    }

    @Test
    void loadDataRestoresSettings() throws Exception {
        File tmp = File.createTempFile("queue", ".json");
        tmp.deleteOnExit();
        AppProperties props = new AppProperties();
        props.getQueue().setPersistenceFile(tmp.getAbsolutePath());

        String settings = "{"
                + "\"player\":{\"playMode\":\"SHUFFLE\",\"fairShuffle\":true,\"allowOfflineShuffle\":true,"
                + "\"voteSkipEnabled\":true,\"voteSkipThreshold\":0.75,\"voteSkipWaitTime\":20,"
                + "\"pauseLocked\":true,\"skipLocked\":true,\"playModeLocked\":true},"
                + "\"roomPassword\":\"room123\",\"streamEnabled\":true,"
                + "\"privateDj\":{\"mode\":\"DJ\",\"fillBlankEnabled\":true,\"joinQueueEnabled\":true,\"custodyEnabled\":true},"
                + "\"systemConfig\":{\"maxQueueSize\":500,\"maxHistorySize\":100,\"maxUserSongs\":50,"
                + "\"maxPlaylistImportSize\":200,\"maxChatHistorySize\":5000,\"minChatIntervalMs\":500,"
                + "\"neteaseEnabled\":true,\"bilibiliEnabled\":false,\"bilibiliMaxDurationMinutes\":15}"
                + "}";
        Files.writeString(tmp.toPath(), "{\"queue\":[],\"history\":[],\"chatHistory\":[],\"settings\":" + settings + "}");

        MusicQueueManager qm = mock(MusicQueueManager.class);
        ChatService chat = mock(ChatService.class);
        MusicPlayerService player = mock(MusicPlayerService.class);
        AuthController auth = mock(AuthController.class);
        LiveStreamService stream = mock(LiveStreamService.class);

        build(qm, chat, props, player, auth, stream).loadData();

        // queue/history/chat 仍按原逻辑恢复
        verify(qm).restore(anyList(), anyList());
        verify(chat).restore(anyList());

        // 播放设置回填
        verify(player).applyPlayerSettings(argThat(p ->
                "SHUFFLE".equals(p.playMode()) && p.fairShuffle() && p.voteSkipEnabled()
                        && p.pauseLocked() && p.playModeLocked()));

        // 房间密码 / 直播开关
        verify(auth).forceSetPassword("room123");
        verify(stream).setEnabled(true);

        // AppProperties 各 Config 回填
        assertEquals(500, props.getQueue().getMaxSize());
        assertEquals(100, props.getQueue().getHistorySize());
        assertEquals(50, props.getQueue().getMaxUserSongs());
        assertEquals(200, props.getPlayer().getMaxPlaylistImportSize());
        assertEquals(5000, props.getChat().getMaxHistorySize());
        assertEquals(500L, props.getChat().getMinIntervalMs());
        assertTrue(props.getNetease().isEnabled());
        assertFalse(props.getBilibili().isEnabled());
        assertEquals(15, props.getBilibili().getMaxDurationMinutes());
        assertEquals("DJ", props.getPrivateDj().getMode());
        assertTrue(props.getPrivateDj().isFillBlankEnabled());
    }

    @Test
    void loadDataOldFileSkipsSettingsRestore() throws Exception {
        File tmp = File.createTempFile("queue", ".json");
        tmp.deleteOnExit();
        AppProperties props = new AppProperties();
        props.getQueue().setPersistenceFile(tmp.getAbsolutePath());
        Files.writeString(tmp.toPath(), "{\"queue\":[],\"history\":[],\"chatHistory\":[]}");

        MusicPlayerService player = mock(MusicPlayerService.class);
        AuthController auth = mock(AuthController.class);
        LiveStreamService stream = mock(LiveStreamService.class);

        build(mock(MusicQueueManager.class), mock(ChatService.class), props, player, auth, stream).loadData();

        verify(player, never()).applyPlayerSettings(any());
        verify(auth, never()).forceSetPassword(anyString());
        verify(stream, never()).setEnabled(anyBoolean());
    }

    @Test
    void loadDataPartialSettingsRestoresOnlyPresentFields() throws Exception {
        File tmp = File.createTempFile("queue", ".json");
        tmp.deleteOnExit();
        AppProperties props = new AppProperties();
        props.getQueue().setPersistenceFile(tmp.getAbsolutePath());
        Files.writeString(tmp.toPath(),
                "{\"queue\":[],\"history\":[],\"chatHistory\":[],"
                + "\"settings\":{\"player\":{\"playMode\":\"REPEAT_ONE\"},\"streamEnabled\":true}}");

        MusicPlayerService player = mock(MusicPlayerService.class);
        AuthController auth = mock(AuthController.class);
        LiveStreamService stream = mock(LiveStreamService.class);

        build(mock(MusicQueueManager.class), mock(ChatService.class), props, player, auth, stream).loadData();

        verify(player).applyPlayerSettings(argThat(p ->
                "REPEAT_ONE".equals(p.playMode()) && p.fairShuffle() == null));
        verify(stream).setEnabled(true);
        verify(auth, never()).forceSetPassword(anyString());
        // 缺省 systemConfig → AppProperties 保持默认
        assertEquals(1000, props.getQueue().getMaxSize());
    }
}
