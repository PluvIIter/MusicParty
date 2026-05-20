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
        
        String type = request.type().toUpperCase();
        if ("ALL".equalsIgnoreCase(type)) {
            musicPlayerService.setAllLocks(request.locked());
            return ResponseEntity.ok(Map.of("message", (request.locked() ? "已开启全频道操作锁定" : "已解除全频道操作锁定")));
        } else {
            musicPlayerService.setLock(type, request.locked());
            String desc = switch (type) {
                case "PAUSE" -> "暂停控制";
                case "SKIP" -> "切歌控制";
                case "SHUFFLE" -> "随机控制";
                default -> type;
            };
            return ResponseEntity.ok(Map.of("message", desc + (request.locked() ? "已锁定" : "已解锁")));
        }
    }

    @PostMapping("/player/action")
    public ResponseEntity<?> playerAction(@RequestHeader("X-Admin-Password") String password, @RequestBody AdminPlayerActionRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        String action = request.action().toUpperCase();
        String msg = switch (action) {
            case "PAUSE" -> {
                musicPlayerService.togglePause("SYSTEM");
                yield "播放状态已切换";
            }
            case "SKIP" -> {
                musicPlayerService.skipToNext("SYSTEM");
                yield "已强制跳过当前歌曲";
            }
            case "SHUFFLE" -> {
                musicPlayerService.toggleShuffle("SYSTEM");
                yield "随机播放主开关已切换";
            }
            case "TOGGLE_FAIR_SHUFFLE" -> {
                musicPlayerService.toggleFairShuffle("SYSTEM");
                yield "随机算法已切换";
            }
            case "TOGGLE_ALLOW_OFFLINE" -> {
                musicPlayerService.toggleAllowOfflineShuffle("SYSTEM");
                yield "离线成员过滤规则已更新";
            }
            default -> null;
        };

        if (msg == null) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(Map.of("message", msg));
    }

    @PostMapping("/room/password")
    public ResponseEntity<?> setRoomPassword(@RequestHeader("X-Admin-Password") String password, @RequestBody AdminRoomPasswordRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        String newPwd = request.password() == null ? "" : request.password();
        authController.forceSetPassword(newPwd);
        musicPlayerService.broadcastPasswordChanged();
        return ResponseEntity.ok(Map.of("message", newPwd.isEmpty() ? "房间已设为公开访问" : "房间访问密码已更新"));
    }

    @PostMapping("/room/clear")
    public ResponseEntity<?> clearData(@RequestHeader("X-Admin-Password") String password, @RequestBody AdminClearRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        String target = request.target().toUpperCase();
        if ("CHAT".equalsIgnoreCase(target)) {
            chatService.clearHistoryAndNotify();
            return ResponseEntity.ok(Map.of("message", "聊天历史记录已清空"));
        } else if ("OFFLINE".equalsIgnoreCase(target)) {
            int count = musicPlayerService.clearOfflineSongs();
            return ResponseEntity.ok(Map.of("message", "已清理 " + count + " 首离线成员的点播歌曲"));
        } else {
            musicPlayerService.clearQueue();
            return ResponseEntity.ok(Map.of("message", "播放队列已全部重置"));
        }
    }

    @PostMapping("/system/reset")
    public ResponseEntity<?> resetSystem(@RequestHeader("X-Admin-Password") String password) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        musicPlayerService.resetSystem();
        return ResponseEntity.ok(Map.of("message", "系统核心已完成全量重置并重启"));
    }

    @PostMapping("/config/cookie")
    public ResponseEntity<?> setCookie(@RequestHeader("X-Admin-Password") String password, @RequestBody AdminCookieRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        if ("netease".equalsIgnoreCase(request.platform())) {
            neteaseMusicApiService.updateCookie(request.value());
            return ResponseEntity.ok(Map.of("message", "网易云音乐凭据已更新"));
        } else if ("bilibili".equalsIgnoreCase(request.platform())) {
            bilibiliMusicApiService.updateSessdata(request.value());
            return ResponseEntity.ok(Map.of("message", "Bilibili SessData 已更新"));
        } else {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/room/stream")
    public ResponseEntity<?> setStream(@RequestHeader("X-Admin-Password") String password, @RequestBody AdminStreamRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        liveStreamService.setEnabled(request.enabled());
        musicPlayerService.broadcastFullPlayerState();
        return ResponseEntity.ok(Map.of("message", request.enabled() ? "直播流同步服务已启动" : "直播流同步服务已停止"));
    }

    @PostMapping("/config/update")
    public ResponseEntity<?> updateConfig(@RequestHeader("X-Admin-Password") String password, @RequestBody AdminConfigUpdateRequest request) {
        if (!isValid(password)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        AppProperties.QueueConfig queue = musicPlayerService.getAppProperties().getQueue();
        AppProperties.PlayerConfig player = musicPlayerService.getAppProperties().getPlayer();
        AppProperties.ChatConfig chat = musicPlayerService.getAppProperties().getChat();

        StringBuilder sb = new StringBuilder();

        if (request.maxSize() != null) { queue.setMaxSize(request.maxSize()); sb.append("队列上限 "); }
        if (request.historySize() != null) { queue.setHistorySize(request.historySize()); sb.append("历史容量 "); }
        if (request.maxUserSongs() != null) { queue.setMaxUserSongs(request.maxUserSongs()); sb.append("点歌上限 "); }
        
        if (request.maxPlaylistImportSize() != null) { player.setMaxPlaylistImportSize(request.maxPlaylistImportSize()); sb.append("导入上限 "); }
        
        if (request.maxChatHistorySize() != null) { chat.setMaxHistorySize(request.maxChatHistorySize()); sb.append("聊天容量 "); }
        if (request.minChatIntervalMs() != null) { chat.setMinIntervalMs(request.minChatIntervalMs()); sb.append("发言频率 "); }
        if (request.maxChatMessageLength() != null) { chat.setMaxMessageLength(request.maxChatMessageLength()); sb.append("消息长度 "); }

        if (request.neteaseEnabled() != null) {
            musicPlayerService.getAppProperties().getNetease().setEnabled(request.neteaseEnabled());
            sb.append(request.neteaseEnabled() ? "已开启网易云 " : "已禁用网易云 ");
        }
        if (request.bilibiliEnabled() != null) {
            musicPlayerService.getAppProperties().getBilibili().setEnabled(request.bilibiliEnabled());
            sb.append(request.bilibiliEnabled() ? "已开启Bilibili " : "已禁用Bilibili ");
        }

        String msg = sb.length() > 0 ? sb.toString().trim() + "已生效" : "系统配置已刷新";

        musicPlayerService.broadcastFullPlayerState();
        return ResponseEntity.ok(Map.of("message", msg));
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
