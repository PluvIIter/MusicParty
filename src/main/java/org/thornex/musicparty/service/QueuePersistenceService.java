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
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    public synchronized void saveNow() throws IOException {
        File file = getPersistenceFile();
        PersistentData data = new PersistentData();
        data.setSchemaVersion(1);
        synchronized (musicQueueManager) {
            data.setQueue(musicQueueManager.getQueueSnapshot());
            data.setPlayedQueueIds(musicQueueManager.getPlayedQueueIds());
            data.setMemberShufflePreferences(musicQueueManager.getMemberShufflePreferences());
        }
        data.setHistory(musicQueueManager.getHistorySnapshot());
        data.setChatHistory(chatService.getHistoryFull());
        data.setSettings(buildSettingsSnapshot());
        objectMapper.writeValue(file, data);
        log.debug("Queue, music history and chat history saved to {}", file.getAbsolutePath());
    }

    synchronized void saveData() {
        try {
            saveNow();
        } catch (Exception e) {
            log.error("Failed to save persistence data", e);
        }
    }

    /** Persist a proposed queue before publishing its ownership to runtime state. Failure propagates to the caller. */
    public synchronized void saveQueueSnapshot(List<MusicQueueItem> entries) throws IOException {
        Path file = getPersistenceFile().toPath().toAbsolutePath().normalize();
        PersistentData data = new PersistentData();
        data.setSchemaVersion(1);
        synchronized (musicQueueManager) {
            data.setQueue(List.copyOf(entries));
            Set<String> playedQueueIds = new HashSet<>(musicQueueManager.getPlayedQueueIds());
            // Filter a copy: a failed proposed snapshot must not prune the live first-play ledger.
            playedQueueIds.retainAll(data.getQueue().stream().map(MusicQueueItem::queueId).collect(Collectors.toSet()));
            data.setPlayedQueueIds(playedQueueIds);
            var preferences = new java.util.HashMap<>(musicQueueManager.getMemberShufflePreferences());
            Set<String> proposedOwners = entries.stream().map(item -> item.enqueuedBy().token()).collect(Collectors.toSet());
            Set<String> removedOwners = musicQueueManager.getQueueSnapshot().stream()
                    .map(item -> item.enqueuedBy().token()).filter(token -> !proposedOwners.contains(token)).collect(Collectors.toSet());
            preferences.keySet().removeAll(removedOwners);
            data.setMemberShufflePreferences(preferences);
        }
        data.setHistory(musicQueueManager.getHistorySnapshot());
        data.setChatHistory(chatService.getHistoryFull());
        data.setSettings(buildSettingsSnapshot());
        Path temporary = Files.createTempFile(file.getParent(), "queue-snapshot-", ".tmp");
        try {
            objectMapper.writeValue(temporary.toFile(), data);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    synchronized void loadData() {
        File file = getPersistenceFile();
        if (!file.exists()) {
            log.info("No persistence file found at {}, starting fresh.", file.getAbsolutePath());
            return;
        }

        try {
            PersistentData data = objectMapper.readValue(file, new TypeReference<PersistentData>() {});

            // 旧版存档（无 schemaVersion 或版本不符）不再拒绝：尽量导入，随后立即按新格式覆写。
            boolean migrated = !Integer.valueOf(1).equals(data.getSchemaVersion());
            if (migrated) {
                Path backup = backupOf(file, "legacy");
                log.warn("Legacy persistence snapshot (schemaVersion={}) at {}; backup kept at {} then content imported",
                        data.getSchemaVersion(), file.getAbsolutePath(), backup);
                data.setSchemaVersion(1);
            }

            musicQueueManager.restore(
                data.getQueue() != null ? data.getQueue() : Collections.emptyList(),
                data.getHistory() != null ? data.getHistory() : Collections.emptyList()
            );

            musicQueueManager.restorePlayedQueueIds(data.getPlayedQueueIds());
            musicQueueManager.restoreMemberShufflePreferences(data.getMemberShufflePreferences());
            chatService.restore(data.getChatHistory() != null ? data.getChatHistory() : Collections.emptyList());

            applySettings(data.getSettings());
            musicPlayerService.prepareRestoredQueue();

            log.info("Restored {} queue items, {} music history items and {} chat messages from {}",
                data.getQueue() != null ? data.getQueue().size() : 0,
                data.getHistory() != null ? data.getHistory().size() : 0,
                data.getChatHistory() != null ? data.getChatHistory().size() : 0,
                file.getAbsolutePath());

            if (migrated) {
                try {
                    saveNow();
                    log.info("Persistence snapshot rewritten as schemaVersion=1 at {}", file.getAbsolutePath());
                } catch (IOException e) {
                    log.warn("Failed to rewrite migrated persistence snapshot at {}; it will be rewritten on the next save",
                            file.getAbsolutePath(), e);
                }
            }
        } catch (Exception e) {
            Path backup = backupOf(file, "unreadable");
            log.error("Failed to load persistence data from {}; original kept at {}, starting fresh and overwriting on the next save",
                    file.getAbsolutePath(), backup, e);
        }
    }

    /** 尽量保留一份旧文件副本（不阻止后续覆写），便于回滚或排查。 */
    private Path backupOf(File file, String reason) {
        try {
            Path source = file.toPath().toAbsolutePath().normalize();
            Path backup = source.resolveSibling(source.getFileName() + "." + reason + "-" + System.currentTimeMillis() + ".bak");
            Files.copy(source, backup, StandardCopyOption.REPLACE_EXISTING);
            return backup;
        } catch (IOException e) {
            log.warn("Failed to back up persistence file {}", file.getAbsolutePath(), e);
            return file.toPath();
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
            if (cfg.retainPlaylist() != null) appProperties.getQueue().setRetainPlaylist(cfg.retainPlaylist());
            if (cfg.maxQueueSize() != null) appProperties.getQueue().setMaxSize(cfg.maxQueueSize());
            if (cfg.maxHistorySize() != null) appProperties.getQueue().setHistorySize(cfg.maxHistorySize());
            if (cfg.maxUserSongs() != null) appProperties.getQueue().setMaxUserSongs(cfg.maxUserSongs());
            if (cfg.maxPlaylistImportSize() != null) appProperties.getPlayer().setMaxPlaylistImportSize(cfg.maxPlaylistImportSize());
            if (cfg.maxChatHistorySize() != null) appProperties.getChat().setMaxHistorySize(cfg.maxChatHistorySize());
            if (cfg.minChatIntervalMs() != null) appProperties.getChat().setMinIntervalMs(cfg.minChatIntervalMs());
            if (cfg.maxChatMessageLength() != null) appProperties.getChat().setMaxMessageLength(cfg.maxChatMessageLength());
            if (cfg.allowPlaylistClaim() != null) appProperties.getQueue().setAllowPlaylistClaim(cfg.allowPlaylistClaim());
            if (cfg.allowSeek() != null) appProperties.getPlayer().setAllowSeek(cfg.allowSeek());
            if (cfg.bilibiliMaxDurationMinutes() != null) appProperties.getBilibili().setMaxDurationMinutes(cfg.bilibiliMaxDurationMinutes());
        }

        if (s.sourceEnabled() != null) {
            s.sourceEnabled().forEach((id, enabled) -> {
                if (enabled != null) musicPlayerService.restoreSourceEnabled(id, enabled);
            });
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
                        appProperties.getBilibili().getMaxDurationMinutes(),
                        appProperties.getChat().getMaxMessageLength(),
                        appProperties.getQueue().isRetainPlaylist(),
                        appProperties.getQueue().isAllowPlaylistClaim(),
                        appProperties.getPlayer().isAllowSeek()),
                appProperties.getSources().entrySet().stream()
                        .filter(entry -> entry.getValue().getEnabled() != null)
                        .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getEnabled())));
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
        private Set<String> playedQueueIds;
        private List<Music> history;
        private List<org.thornex.musicparty.dto.ChatMessage> chatHistory;
        private SettingsSnapshot settings;
        private Integer schemaVersion;
        private Map<String, Boolean> memberShufflePreferences;
    }
}
