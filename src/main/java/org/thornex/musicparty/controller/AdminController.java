package org.thornex.musicparty.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.service.MusicPlayerService;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final MusicPlayerService musicPlayerService;
    private final String adminPassword;
    private final AuthController authController;

    public AdminController(MusicPlayerService musicPlayerService, AppProperties appProperties, AuthController authController) {
        this.musicPlayerService = musicPlayerService;
        this.adminPassword = appProperties.getAdminPassword();
        this.authController = authController;
    }

    @PostMapping("/reset")
    public ResponseEntity<?> resetSystem(@RequestBody Map<String, String> body) {
        String inputPassword = body.get("password");

        if (adminPassword != null && adminPassword.equals(inputPassword)) {
            musicPlayerService.resetSystem();
            authController.resetRoomPassword();
            return ResponseEntity.ok(Map.of("message", "SYSTEM PURGED"));
        } else {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "ACCESS DENIED"));
        }
    }
}