package org.thornex.musicparty.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.AdminCommandRequest;
import org.thornex.musicparty.service.MusicPlayerService;
import org.thornex.musicparty.service.api.BilibiliMusicApiService;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final MusicPlayerService musicPlayerService;
    private final String adminPassword;
    private final AuthController authController;
    private final NeteaseMusicApiService neteaseMusicApiService;
    private final BilibiliMusicApiService bilibiliMusicApiService;

    public AdminController(MusicPlayerService musicPlayerService, AppProperties appProperties, AuthController authController, NeteaseMusicApiService neteaseMusicApiService, BilibiliMusicApiService bilibiliMusicApiService) {
        this.musicPlayerService = musicPlayerService;
        this.adminPassword = appProperties.getAdminPassword();
        this.authController = authController;
        this.neteaseMusicApiService = neteaseMusicApiService;
        this.bilibiliMusicApiService = bilibiliMusicApiService;
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
            case "//RESET":
                musicPlayerService.resetSystem();
                return ResponseEntity.ok(Map.of("message", "SYSTEM PURGED"));

            case "//CLEAR":
                musicPlayerService.clearQueue();
                return ResponseEntity.ok(Map.of("message", "QUEUE CLEARED"));

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