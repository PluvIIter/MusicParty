package org.thornex.musicparty.service;

import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import java.io.IOException;
import java.util.*;

/** Explicit opt-in recovery: only the connected session may receive an absent owner's playlist. */
@Service
public class PlaylistClaimService {
    private final UserService users;
    private final MusicQueueManager queue;
    private final MusicPlayerService player;
    private final QueuePersistenceService persistence;
    private final AppProperties properties;

    public PlaylistClaimService(UserService users, MusicQueueManager queue, MusicPlayerService player,
                                QueuePersistenceService persistence, AppProperties properties) {
        this.users = users; this.queue = queue; this.player = player;
        this.persistence = persistence; this.properties = properties;
    }
    public record Claimed(int claimed, UserSummary member) {}

    public Claimed claim(String sessionId, String sourceToken) throws IOException {
        if (sourceToken == null || sourceToken.isBlank() || sourceToken.length() > 200
                || "SYSTEM".equals(sourceToken) || "ADMIN".equals(sourceToken) || sourceToken.startsWith("__"))
            throw new IllegalArgumentException("该歌单不可认领");
        // Identity mutations use users; player paths acquire player before queue; persistence acquires persistence before queue.
        synchronized (users) {
            synchronized (player) {
                synchronized (persistence) {
                    synchronized (queue) {
                        if (!properties.getQueue().isAllowPlaylistClaim())
                            throw new IllegalArgumentException("管理员已关闭歌单认领");
                        List<MusicQueueItem> before = queue.getQueueSnapshot();
                        List<MusicQueueItem> owned = before.stream().filter(i -> sourceToken.equals(i.enqueuedBy().token())).toList();
                        if (owned.isEmpty()) throw new IllegalArgumentException("该歌单已被认领或已经清空");
                        String name = owned.get(0).enqueuedBy().name();
                        if (name == null || name.isBlank() || name.length() > 20 || !name.equals(name.trim())
                                || name.toLowerCase(Locale.ROOT).startsWith("guest") || name.startsWith("游客")
                                || owned.stream().anyMatch(i -> i.enqueuedBy().isGuest()
                                        || MusicQueueManager.FM_MARKER_ID.equals(i.music().platform())))
                            throw new IllegalArgumentException("该歌单不可认领");
                        UserSummary member = users.preparePlaylistClaim(sessionId, sourceToken, name);
                        List<MusicQueueItem> after = new ArrayList<>(before.size());
                        for (MusicQueueItem item : before) {
                            boolean replace = sourceToken.equals(item.enqueuedBy().token()) || member.token().equals(item.enqueuedBy().token());
                            after.add(replace ? new MusicQueueItem(item.queueId(), item.music(), member, item.status(), item.priority()) : item);
                        }
                        // No runtime changes occur until the full queue and settings are durable.
                        persistence.saveQueueSnapshot(after);
                        queue.commitPlaylistClaim(after, sourceToken, member.token());
                        player.commitPlaylistClaim(sourceToken, member);
                        users.completePlaylistClaim(sourceToken, member);
                        return new Claimed(owned.size(), member);
                    }
                }
            }
        }
    }
}
