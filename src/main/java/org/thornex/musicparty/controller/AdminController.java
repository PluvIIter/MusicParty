package org.thornex.musicparty.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.service.ChatService;
import org.thornex.musicparty.service.MusicPlayerService;
import org.thornex.musicparty.service.api.BilibiliMusicApiService;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import org.thornex.musicparty.service.stream.LiveStreamService;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final MusicPlayerService musicPlayerService;
    private final ChatService chatService;
    private final String adminPassword;
    private final AuthController authController;
    private final NeteaseMusicApiService neteaseMusicApiService;
    private final BilibiliMusicApiService bilibiliMusicApiService;
    private final LiveStreamService liveStreamService;

    public AdminController(MusicPlayerService musicPlayerService, ChatService chatService, AppProperties appProperties, AuthController authController, NeteaseMusicApiService neteaseMusicApiService, BilibiliMusicApiService bilibiliMusicApiService, LiveStreamService liveStreamService) {
        this.musicPlayerService = musicPlayerService;
        this.chatService = chatService;
        this.adminPassword = appProperties.getAdminPassword();
        this.authController = authController;
        this.neteaseMusicApiService = neteaseMusicApiService;
        this.bilibiliMusicApiService = bilibiliMusicApiService;
        this.liveStreamService = liveStreamService;
    }

    private boolean isValid(String password) {
        return adminPassword != null && adminPassword.equals(password);
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody AdminVerifyRequest request) {
        if (isValid(request.password())) {
            return ResponseEntity.ok(Map.of("message", "VERIFIED"));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "ACCESS DENIED"));
    }

    @PostMapping("/lock")
    public ResponseEntity<?> setLock(@RequestHeader("X-Admin-Password") String password, @RequestBody AdminLockRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        
        if ("ALL".equalsIgnoreCase(request.type())) {
            musicPlayerService.setAllLocks(request.locked());
        } else {
            musicPlayerService.setLock(request.type().toUpperCase(), request.locked());
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/player/action")
    public ResponseEntity<?> playerAction(@RequestHeader("X-Admin-Password") String password, @RequestBody AdminPlayerActionRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        switch (request.action().toUpperCase()) {
            case "PAUSE" -> musicPlayerService.togglePause("SYSTEM");
            case "SKIP" -> musicPlayerService.skipToNext("SYSTEM");
            case "SHUFFLE" -> musicPlayerService.toggleShuffle("SYSTEM");
            default -> { return ResponseEntity.badRequest().build(); }
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/room/password")
    public ResponseEntity<?> setRoomPassword(@RequestHeader("X-Admin-Password") String password, @RequestBody AdminRoomPasswordRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        authController.forceSetPassword(request.password() == null ? "" : request.password());
        musicPlayerService.broadcastPasswordChanged();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/room/clear")
    public ResponseEntity<?> clearData(@RequestHeader("X-Admin-Password") String password, @RequestBody AdminClearRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        if ("CHAT".equalsIgnoreCase(request.target())) {
            chatService.clearHistoryAndNotify();
        } else {
            musicPlayerService.clearQueue();
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/system/reset")
    public ResponseEntity<?> resetSystem(@RequestHeader("X-Admin-Password") String password) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        musicPlayerService.resetSystem();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/config/cookie")
    public ResponseEntity<?> setCookie(@RequestHeader("X-Admin-Password") String password, @RequestBody AdminCookieRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        if ("netease".equalsIgnoreCase(request.platform())) {
            neteaseMusicApiService.updateCookie(request.value());
        } else if ("bilibili".equalsIgnoreCase(request.platform())) {
            bilibiliMusicApiService.updateSessdata(request.value());
        } else {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/room/stream")
    public ResponseEntity<?> setStream(@RequestHeader("X-Admin-Password") String password, @RequestBody AdminStreamRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        liveStreamService.setEnabled(request.enabled());
        return ResponseEntity.ok().build();
    }

    // Keep compatibility for now or remove if sure
    @Deprecated
    @PostMapping("/command")
    public ResponseEntity<?> handleAdminCommand(@RequestBody AdminCommandRequest request) {
        if (!isValid(request.password())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "ACCESS DENIED"));
        }
        // ... (original implementation if still needed, but I'll remove it since we are refactoring)
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of("message", "This endpoint is deprecated. Use structured endpoints."));
    }
}
