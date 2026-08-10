package org.thornex.musicparty.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.controller.AuthController;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.MusicQueueItem;
import org.thornex.musicparty.dto.SettingsSnapshot;
import org.thornex.musicparty.service.stream.LiveStreamService;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueuePersistenceService {

    private final MusicQueueManager musicQueueManager;
    private final ChatService chatService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final MusicPlayerService musicPlayerService;
    private final AuthController authController;
    private final LiveStreamService liveStreamService;

    @PostConstruct
    public void init() {
        loadData();
    }

    @PreDestroy
    public void cleanup() {
        saveData();
    }

    @Scheduled(fixedDelayString = "${app.music-api.queue.persistence-interval-ms:60000}")
    public void scheduledSave() {
        saveData();
    }

    synchronized void saveData() {
        try {
            File file = getPersistenceFile();
            PersistentData data = new PersistentData();
            data.setQueue(musicQueueManager.getQueueSnapshot());
            data.setHistory(musicQueueManager.getHistorySnapshot());
            data.setChatHistory(chatService.getHistoryFull());
            data.setSettings(buildSettingsSnapshot());

            objectMapper.writeValue(file, data);
            log.debug("Queue, music history and chat history saved to {}", file.getAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to save persistence data", e);
        }
    }

    synchronized void loadData() {
        File file = getPersistenceFile();
        if (!file.exists()) {
            log.info("No persistence file found at {}, starting fresh.", file.getAbsolutePath());
            return;
        }

        try {
            PersistentData data = objectMapper.readValue(file, new TypeReference<PersistentData>() {});
            
            musicQueueManager.restore(
                data.getQueue() != null ? data.getQueue() : Collections.emptyList(),
                data.getHistory() != null ? data.getHistory() : Collections.emptyList()
            );

            chatService.restore(data.getChatHistory() != null ? data.getChatHistory() : Collections.emptyList());

            applySettings(data.getSettings());

            log.info("Restored {} queue items, {} music history items and {} chat messages from {}",
                data.getQueue() != null ? data.getQueue().size() : 0, 
                data.getHistory() != null ? data.getHistory().size() : 0, 
                data.getChatHistory() != null ? data.getChatHistory().size() : 0,
                file.getAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to load persistence data from {}", file.getAbsolutePath(), e);
        }
    }

    private void applySettings(SettingsSnapshot s) {
        if (s == null) {
            log.info("No settings section in persistence file, skipping settings restore.");
            return;
        }

        if (s.player() != null) {
            musicPlayerService.applyPlayerSettings(s.player());
        }

        if (s.privateDj() != null) {
            AppProperties.PrivateDjConfig c = appProperties.getPrivateDj();
            if (s.privateDj().mode() != null) c.setMode(s.privateDj().mode());
            if (s.privateDj().fillBlankEnabled() != null) c.setFillBlankEnabled(s.privateDj().fillBlankEnabled());
            if (s.privateDj().joinQueueEnabled() != null) c.setJoinQueueEnabled(s.privateDj().joinQueueEnabled());
            if (s.privateDj().custodyEnabled() != null) c.setCustodyEnabled(s.privateDj().custodyEnabled());
        }

        if (s.systemConfig() != null) {
            SettingsSnapshot.SystemConfigSettings cfg = s.systemConfig();
            if (cfg.maxQueueSize() != null) appProperties.getQueue().setMaxSize(cfg.maxQueueSize());
            if (cfg.maxHistorySize() != null) appProperties.getQueue().setHistorySize(cfg.maxHistorySize());
            if (cfg.maxUserSongs() != null) appProperties.getQueue().setMaxUserSongs(cfg.maxUserSongs());
            if (cfg.maxPlaylistImportSize() != null) appProperties.getPlayer().setMaxPlaylistImportSize(cfg.maxPlaylistImportSize());
            if (cfg.maxChatHistorySize() != null) appProperties.getChat().setMaxHistorySize(cfg.maxChatHistorySize());
            if (cfg.minChatIntervalMs() != null) appProperties.getChat().setMinIntervalMs(cfg.minChatIntervalMs());
            if (cfg.neteaseEnabled() != null) appProperties.getNetease().setEnabled(cfg.neteaseEnabled());
            if (cfg.bilibiliEnabled() != null) appProperties.getBilibili().setEnabled(cfg.bilibiliEnabled());
            if (cfg.bilibiliMaxDurationMinutes() != null) appProperties.getBilibili().setMaxDurationMinutes(cfg.bilibiliMaxDurationMinutes());
        }

        if (s.roomPassword() != null) authController.forceSetPassword(s.roomPassword());
        if (s.streamEnabled() != null) liveStreamService.setEnabled(s.streamEnabled());

        log.info("Restored persisted runtime settings from {}", appProperties.getQueue().getPersistenceFile());
    }

    private SettingsSnapshot buildSettingsSnapshot() {
        return new SettingsSnapshot(
                musicPlayerService.getPlayerSettings(),
                authController.getRawPassword(),
                liveStreamService.isEnabled(),
                new SettingsSnapshot.PrivateDjSettings(
                        appProperties.getPrivateDj().getMode(),
                        appProperties.getPrivateDj().isFillBlankEnabled(),
                        appProperties.getPrivateDj().isJoinQueueEnabled(),
                        appProperties.getPrivateDj().isCustodyEnabled()),
                new SettingsSnapshot.SystemConfigSettings(
                        appProperties.getQueue().getMaxSize(),
                        appProperties.getQueue().getHistorySize(),
                        appProperties.getQueue().getMaxUserSongs(),
                        appProperties.getPlayer().getMaxPlaylistImportSize(),
                        appProperties.getChat().getMaxHistorySize(),
                        appProperties.getChat().getMinIntervalMs(),
                        appProperties.getNetease().isEnabled(),
                        appProperties.getBilibili().isEnabled(),
                        appProperties.getBilibili().getMaxDurationMinutes()));
    }

    private File getPersistenceFile() {
        String path = appProperties.getQueue().getPersistenceFile();
        File file = new File(path);
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        return file;
    }

    @Data
    private static class PersistentData {
        private List<MusicQueueItem> queue;
        private List<Music> history;
        private List<org.thornex.musicparty.dto.ChatMessage> chatHistory;
        private SettingsSnapshot settings;
    }
}
