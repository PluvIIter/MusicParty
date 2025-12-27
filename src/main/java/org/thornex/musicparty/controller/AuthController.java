// File Path: src\main\java\org\thornex\musicparty\controller\AuthController.java

package org.thornex.musicparty.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.thornex.musicparty.config.AppProperties;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    // 使用 AtomicReference 保证线程安全
    // null 表示未初始化（需要 Setup）
    // "" (空字符串) 表示已初始化，但不需要密码
    // "xxx" 表示已初始化，且有密码
    private final AtomicReference<String> roomPassword = new AtomicReference<>(null);
    private final String adminPassword;

    public AuthController(AppProperties appProperties) {
        // 从配置中获取管理员密码
        this.adminPassword = appProperties.getAdminPassword();
    }

    public void resetRoomPassword() {
        roomPassword.set(null); // 恢复到未初始化状态
    }

    /**
     * 检查房间状态
     * isSetup: 是否已经完成了初始化设置
     * hasProtection: 是否开启了密码保护
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Boolean>> getStatus() {
        String current = roomPassword.get();
        boolean isSetup = current != null;
        // 只有当已设置且密码不为空时，才算有保护
        boolean hasProtection = isSetup && !current.isEmpty();

        return ResponseEntity.ok(Map.of(
                "isSetup", isSetup,
                "hasProtection", hasProtection
        ));
    }

    /**
     * 设置密码 (只有当前未设置密码时才允许)
     * 允许设置为空字符串，代表不需要密码
     */
    @PostMapping("/setup")
    public synchronized ResponseEntity<?> setupPassword(@RequestBody Map<String, String> body) {
        // 如果已经设置过密码，禁止再次设置（防止并发重置）
        if (roomPassword.get() != null) {
            return ResponseEntity.status(403).body("Password already set");
        }

        // 获取密码，如果是 null 则视为空字符串
        String newPassword = body.getOrDefault("password", "");

        // 保存（可能是空字符串）
        roomPassword.set(newPassword);
        return ResponseEntity.ok(Map.of("message", "Password set successfully"));
    }

    /**
     * 验证密码
     */
    @PostMapping("/verify")
    public ResponseEntity<?> verifyPassword(@RequestBody Map<String, String> body) {
        String inputPassword = body.getOrDefault("password", "");
        String currentPassword = roomPassword.get();

        // 1. 如果还没初始化，理论上应该去 setup，但暂时允许通过
        if (currentPassword == null) {
            return ResponseEntity.ok(Map.of("valid", true));
        }

        // 2. 如果是无密码模式（空字符串），直接通过
        if (currentPassword.isEmpty()) {
            return ResponseEntity.ok(Map.of("valid", true));
        }

        // 3. 如果输入的是管理员密码，直接通过 (万能钥匙)
        if (adminPassword != null && adminPassword.equals(inputPassword)) {
            return ResponseEntity.ok(Map.of("valid", true));
        }

        // 3. 比对密码
        if (currentPassword.equals(inputPassword)) {
            return ResponseEntity.ok(Map.of("valid", true));
        } else {
            return ResponseEntity.status(401).body(Map.of("valid", false));
        }
    }
}