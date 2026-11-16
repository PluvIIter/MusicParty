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

    @Test
    void retentionRoundTripsAndPartialCurrentSettingsKeepConfiguredValue() throws Exception {
        File tmp = File.createTempFile("retention", ".json");
        tmp.deleteOnExit();
        AppProperties props = new AppProperties();
        props.getQueue().setPersistenceFile(tmp.getAbsolutePath());
        QueuePersistenceService persistence = build(mock(MusicQueueManager.class), mock(ChatService.class),
                props, mock(MusicPlayerService.class), mock(AuthController.class), mock(LiveStreamService.class));
        for (boolean enabled : new boolean[]{true, false}) {
            props.getQueue().setRetainPlaylist(enabled);
            persistence.saveNow();
            props.getQueue().setRetainPlaylist(!enabled);
            persistence.loadData();
            assertEquals(enabled, props.getQueue().isRetainPlaylist());
        }
        props.getQueue().setRetainPlaylist(true);
        Files.writeString(tmp.toPath(), "{\"schemaVersion\":1,\"queue\":[],\"settings\":{\"systemConfig\":{\"maxQueueSize\":77}}}");
        persistence.loadData();
        assertTrue(props.getQueue().isRetainPlaylist());
        assertEquals(77, props.getQueue().getMaxSize());
    }

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
        props.getSources().computeIfAbsent("netease", ignored -> new AppProperties.SourceConfig()).setEnabled(true);
        props.getSources().computeIfAbsent("bilibili", ignored -> new AppProperties.SourceConfig()).setEnabled(false);
        props.getQueue().setMaxSize(500);
        props.getChat().setMaxMessageLength(512);

        MusicQueueManager qm = mock(MusicQueueManager.class);
        when(qm.getQueueSnapshot()).thenReturn(List.of());
        when(qm.getHistorySnapshot()).thenReturn(List.of());
        ChatService chat = mock(ChatService.class);
        when(chat.getHistoryFull()).thenReturn(List.of());
        MusicPlayerService player = mock(MusicPlayerService.class);
        when(player.getPlayerSettings()).thenReturn(new SettingsSnapshot.PlayerSettings(
                "SHUFFLE", true, true, 0.75, 20, true, true, true));
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
        assertEquals(512, snap.systemConfig().maxChatMessageLength());
        assertTrue(snap.sourceEnabled().get("netease"));
        assertFalse(snap.sourceEnabled().get("bilibili"));
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
                "SEQUENTIAL", null, null, null, null, null, null, null));
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
                + "\"player\":{\"playMode\":\"SHUFFLE\",\"allowOfflineShuffle\":true,"
                + "\"voteSkipEnabled\":true,\"voteSkipThreshold\":0.75,\"voteSkipWaitTime\":20,"
                + "\"pauseLocked\":true,\"skipLocked\":true,\"playModeLocked\":true},"
                + "\"roomPassword\":\"room123\",\"streamEnabled\":true,"
                + "\"privateDj\":{\"mode\":\"DJ\",\"fillBlankEnabled\":true,\"joinQueueEnabled\":true,\"custodyEnabled\":true},"
                + "\"systemConfig\":{\"maxQueueSize\":500,\"maxHistorySize\":100,\"maxUserSongs\":50,"
                + "\"maxPlaylistImportSize\":200,\"maxChatHistorySize\":5000,\"minChatIntervalMs\":500,"
                + "\"bilibiliMaxDurationMinutes\":15}"
                + ",\"sourceEnabled\":{\"netease\":true,\"bilibili\":false}}";
        Files.writeString(tmp.toPath(), "{\"schemaVersion\":1,\"queue\":[],\"history\":[],\"chatHistory\":[],\"settings\":" + settings + "}");

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
                "SHUFFLE".equals(p.playMode()) && p.voteSkipEnabled()
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
        verify(player).restoreSourceEnabled("netease", true);
        verify(player).restoreSourceEnabled("bilibili", false);
        assertEquals(15, props.getBilibili().getMaxDurationMinutes());
        assertEquals("DJ", props.getPrivateDj().getMode());
        assertTrue(props.getPrivateDj().isFillBlankEnabled());
    }

    @Test
    void loadDataMissingSettingsDoesNotApplyPlayerSettings() throws Exception {
        File tmp = File.createTempFile("queue", ".json");
        tmp.deleteOnExit();
        AppProperties props = new AppProperties();
        props.getQueue().setPersistenceFile(tmp.getAbsolutePath());
        Files.writeString(tmp.toPath(), "{\"schemaVersion\":1,\"queue\":[],\"history\":[],\"chatHistory\":[]}");

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
                "{\"schemaVersion\":1,\"queue\":[],\"history\":[],\"chatHistory\":[],"
                + "\"settings\":{\"player\":{\"playMode\":\"REPEAT_ONE\"},\"streamEnabled\":true}}");

        MusicPlayerService player = mock(MusicPlayerService.class);
        AuthController auth = mock(AuthController.class);
        LiveStreamService stream = mock(LiveStreamService.class);

        build(mock(MusicQueueManager.class), mock(ChatService.class), props, player, auth, stream).loadData();

        verify(player).applyPlayerSettings(argThat(p ->
                "REPEAT_ONE".equals(p.playMode()) && p.voteSkipEnabled() == null));
        verify(stream).setEnabled(true);
        verify(auth, never()).forceSetPassword(anyString());
        // 缺省 systemConfig → AppProperties 保持默认
        assertEquals(1000, props.getQueue().getMaxSize());
    }

    @Test
    void saveAndReloadRestoresChatMessageLength() throws Exception {
        File tmp = File.createTempFile("queue-chat-length", ".json");
        tmp.deleteOnExit();
        AppProperties props = new AppProperties();
        props.getQueue().setPersistenceFile(tmp.getAbsolutePath());
        props.getChat().setMaxMessageLength(512);

        MusicQueueManager queue = mock(MusicQueueManager.class);
        when(queue.getQueueSnapshot()).thenReturn(List.of());
        when(queue.getHistorySnapshot()).thenReturn(List.of());
        ChatService chat = mock(ChatService.class);
        when(chat.getHistoryFull()).thenReturn(List.of());
        QueuePersistenceService persistence = build(queue, chat, props,
                mock(MusicPlayerService.class), mock(AuthController.class), mock(LiveStreamService.class));

        persistence.saveData();
        props.getChat().setMaxMessageLength(200);
        persistence.loadData();

        assertEquals(512, props.getChat().getMaxMessageLength());
    }

    @Test
    void partialSystemConfigKeepsConfiguredChatMessageLength() throws Exception {
        File tmp = File.createTempFile("queue-old-chat-length", ".json");
        tmp.deleteOnExit();
        Files.writeString(tmp.toPath(),
                "{\"schemaVersion\":1,\"queue\":[],\"history\":[],\"chatHistory\":[],"
                + "\"settings\":{\"systemConfig\":{\"maxQueueSize\":500}}}");
        AppProperties props = new AppProperties();
        props.getQueue().setPersistenceFile(tmp.getAbsolutePath());
        props.getChat().setMaxMessageLength(333);

        build(mock(MusicQueueManager.class), mock(ChatService.class), props,
                mock(MusicPlayerService.class), mock(AuthController.class), mock(LiveStreamService.class)).loadData();

        assertEquals(500, props.getQueue().getMaxSize());
        assertEquals(333, props.getChat().getMaxMessageLength());
    }

}
