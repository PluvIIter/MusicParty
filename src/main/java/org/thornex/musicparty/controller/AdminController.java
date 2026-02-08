package org.thornex.musicparty.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.AdminCommandRequest;
import org.thornex.musicparty.service.ChatService;
import org.thornex.musicparty.service.MusicPlayerService;
import org.thornex.musicparty.service.api.BilibiliMusicApiService;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final MusicPlayerService musicPlayerService;
    private final ChatService chatService;
    private final String adminPassword;
    private final AuthController authController;
    private final NeteaseMusicApiService neteaseMusicApiService;
    private final BilibiliMusicApiService bilibiliMusicApiService;
    private final org.thornex.musicparty.service.stream.LiveStreamService liveStreamService;

    public AdminController(MusicPlayerService musicPlayerService, ChatService chatService, AppProperties appProperties, AuthController authController, NeteaseMusicApiService neteaseMusicApiService, BilibiliMusicApiService bilibiliMusicApiService, org.thornex.musicparty.service.stream.LiveStreamService liveStreamService) {
        this.musicPlayerService = musicPlayerService;
        this.chatService = chatService;
        this.adminPassword = appProperties.getAdminPassword();
        this.authController = authController;
        this.neteaseMusicApiService = neteaseMusicApiService;
        this.bilibiliMusicApiService = bilibiliMusicApiService;
        this.liveStreamService = liveStreamService;
    }

    @PostMapping("/command")
    public ResponseEntity<?> handleAdminCommand(@RequestBody AdminCommandRequest request) {
        if (adminPassword == null || !adminPassword.equals(request.password())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "ACCESS DENIED"));
        }

        String command = request.command().trim();
        if (!command.startsWith("//")) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid command format."));
        }

        String[] parts = command.split("\\s+", 3);
        String action = parts[0].toUpperCase();

        switch (action) {
            case "//STREAM":
                if (parts.length < 2) {
                    return ResponseEntity.badRequest().body(Map.of("message", "Usage: //STREAM <ON/OFF>"));
                }
                String subCmd = parts[1].toUpperCase();
                if ("ON".equals(subCmd)) {
                    liveStreamService.setEnabled(true);
                    return ResponseEntity.ok(Map.of("message", "STREAM SERVICE ENABLED"));
                } else if ("OFF".equals(subCmd)) {
                    liveStreamService.setEnabled(false);
                    return ResponseEntity.ok(Map.of("message", "STREAM SERVICE DISABLED"));
                } else {
                    return ResponseEntity.badRequest().body(Map.of("message", "Invalid stream command"));
                }

            case "//LOCK":
                if (parts.length < 3) {
                    if (parts.length < 2) {
                        return ResponseEntity.badRequest().body(Map.of("message", "Usage: //LOCK <TYPE> <ON/OFF>. TYPE: PAUSE, SKIP, SHUFFLE, ALL"));
                    }
                    return ResponseEntity.badRequest().body(Map.of("message", "Missing ON/OFF. Usage: //LOCK <TYPE> <ON/OFF>"));
                }
                String type = parts[1].toUpperCase();
                String state = parts[2].toUpperCase();
                boolean locked = "ON".equals(state);

                if ("ALL".equals(type)) {
                    musicPlayerService.setAllLocks(locked);
                    return ResponseEntity.ok(Map.of("message", "ALL LOCKS SET TO " + locked));
                } else if (Set.of("PAUSE", "SKIP", "SHUFFLE").contains(type)) {
                    musicPlayerService.setLock(type, locked);
                    return ResponseEntity.ok(Map.of("message", type + " LOCK SET TO " + locked));
                } else {
                    return ResponseEntity.badRequest().body(Map.of("message", "Invalid lock type: " + type));
                }

            case "//PAUSE":
                musicPlayerService.togglePause("SYSTEM");
                return ResponseEntity.ok(Map.of("message", "TOGGLE PAUSE (SYSTEM OVERRIDE)"));

            case "//SKIP":
                musicPlayerService.skipToNext("SYSTEM");
                return ResponseEntity.ok(Map.of("message", "SKIP TO NEXT (SYSTEM OVERRIDE)"));

            case "//SHUFFLE":
                musicPlayerService.toggleShuffle("SYSTEM");
                return ResponseEntity.ok(Map.of("message", "TOGGLE SHUFFLE (SYSTEM OVERRIDE)"));

            case "//RESET":
                musicPlayerService.resetSystem();
                return ResponseEntity.ok(Map.of("message", "SYSTEM PURGED"));

            case "//CLEAR":
                if (parts.length < 2 || "QUEUE".equalsIgnoreCase(parts[1])) {
                    musicPlayerService.clearQueue();
                    return ResponseEntity.ok(Map.of("message", "QUEUE CLEARED"));
                } else if ("CHAT".equalsIgnoreCase(parts[1])) {
                    chatService.clearHistoryAndNotify();
                    return ResponseEntity.ok(Map.of("message", "CHAT HISTORY CLEARED"));
                } else {
                    return ResponseEntity.badRequest().body(Map.of("message", "Usage: //CLEAR <QUEUE/CHAT>"));
                }

            case "//PASS":
                if (parts.length < 2) {
                    return ResponseEntity.badRequest().body(Map.of("message", "Usage: //PASS <new_password>"));
                }
                String newRoomPassword = parts[1];
                authController.forceSetPassword(newRoomPassword);
                musicPlayerService.broadcastPasswordChanged();
                return ResponseEntity.ok(Map.of("message", "ROOM PASSWORD UPDATED"));

            case "//OPEN":
                authController.forceSetPassword("");
                musicPlayerService.broadcastPasswordChanged();
                return ResponseEntity.ok(Map.of("message", "ROOM IS NOW PUBLIC"));

            case "//COOKIE":
                if (parts.length < 3) {
                    return ResponseEntity.badRequest().body(Map.of("message", "Usage: //COOKIE <platform> <cookie_string>"));
                }
                String platform = parts[1].toLowerCase();
                String cookie = parts[2];

                if ("netease".equals(platform)) {
                    neteaseMusicApiService.updateCookie(cookie);
                    return ResponseEntity.ok(Map.of("message", "Netease cookie updated."));
                } else if ("bilibili".equals(platform)) {
                    bilibiliMusicApiService.updateSessdata(cookie);
                    return ResponseEntity.ok(Map.of("message", "Bilibili SESSDATA updated."));
                } else {
                    return ResponseEntity.badRequest().body(Map.of("message", "Unsupported platform: " + platform));
                }

            default:
                return ResponseEntity.badRequest().body(Map.of("message", "Unknown command: " + action));
        }
    }
}