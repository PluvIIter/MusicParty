package org.thornex.musicparty.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.service.*;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/playlist-claim")
public class PlaylistClaimSettingsController {
    private final AppProperties properties;
    private final MusicQueueManager queue;
    private final MusicPlayerService player;
    private final QueuePersistenceService persistence;
    public PlaylistClaimSettingsController(AppProperties properties, MusicQueueManager queue, MusicPlayerService player, QueuePersistenceService persistence) {
        this.properties=properties; this.queue=queue; this.player=player; this.persistence=persistence;
    }
    private boolean allowed(String password) { return properties.getAdminPassword()!=null && properties.getAdminPassword().equals(password); }
    @GetMapping
    public ResponseEntity<?> get(@RequestHeader(value="X-Admin-Password",required=false) String password) {
        if (!allowed(password)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok().header("Cache-Control","no-store").body(Map.of("allowPlaylistClaim",properties.getQueue().isAllowPlaylistClaim()));
    }
    @PostMapping
    public ResponseEntity<?> update(@RequestHeader(value="X-Admin-Password",required=false) String password, @RequestBody Map<String,Object> request) throws IOException {
        if (!allowed(password)) return ResponseEntity.status(403).build();
        if (!(request.get("allowPlaylistClaim") instanceof Boolean value)) return ResponseEntity.badRequest().build();
        synchronized (persistence) {
            synchronized (queue) {
                boolean before=properties.getQueue().isAllowPlaylistClaim();
                properties.getQueue().setAllowPlaylistClaim(value);
                try { persistence.saveQueueSnapshot(queue.getQueueSnapshot()); }
                catch (IOException error) { properties.getQueue().setAllowPlaylistClaim(before); throw error; }
            }
        }
        player.broadcastFullPlayerState();
        return ResponseEntity.ok().header("Cache-Control","no-store").body(Map.of("allowPlaylistClaim",value,"message","歌单认领设置已保存并生效"));
    }
}
