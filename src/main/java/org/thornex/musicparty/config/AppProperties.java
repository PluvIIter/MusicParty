package org.thornex.musicparty.config;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.music-api")
@Data
public class AppProperties {
    private NeteaseApiConfig  netease;
    private BilibiliApiConfig bilibili;
    private String adminPassword;
    private String baseUrl;
    private String authorName = "ThorNex";
    private String backWords = "THORNEX";
    private String ffmpegPath = "ffmpeg"; // 默认使用环境变量中的 ffmpeg

    // 新增配置项
    private QueueConfig queue = new QueueConfig();
    private PlayerConfig player = new PlayerConfig();
    private ChatConfig chat = new ChatConfig();
    private CacheConfig cache = new CacheConfig();
    private AuthConfig auth = new AuthConfig();

    @Data
    public static class QueueConfig {
        private int maxSize = 1000;
        private int historySize = 50;
        private int maxUserSongs = 100;
        private String persistenceFile = "data/queue-data.json";
        private long persistenceIntervalMs = 60000; // Default save every 1 minute
    }

    @Data
    public static class PlayerConfig {
        private int maxPlaylistImportSize = 100;
        private boolean voteSkipEnabled = false;
        private double voteSkipThreshold = 0.5;
        private int voteSkipWaitTime = 15;
    }

    @Data
    public static class ChatConfig {
        private int maxHistorySize = 1000;
        private long minIntervalMs = 1000;
        private int maxMessageLength = 200;
    }

    @Data
    public static class CacheConfig {
        private org.springframework.util.unit.DataSize maxSize = org.springframework.util.unit.DataSize.ofGigabytes(1);
    }

    @Data
    public static class AuthConfig {
        private boolean rateLimitEnabled = true;
        private int maxAttempts = 5;
        private int windowSeconds = 60;
        private int blockDurationSeconds = 300;
    }

    @Data
    public static class ApiConfig {
        private String baseUrl;
    }

    @EqualsAndHashCode(callSuper = true)
    @Data
    public static class BilibiliApiConfig extends ApiConfig {
        /**
         * 完整 Cookie（可选，与网易云 Cookie 一致）：浏览器登录 B站后复制完整 Cookie 请求头。
         * 不填仅导致 B站源不可用，不影响其他音乐源。
         * 须含 buvid3/buvid4/SESSDATA/bili_jct/_uuid 等，请求才接近真实登录浏览器，
         * 才能解析高音质 DASH 音频并降低风控概率。
         */
        private String cookie;
        private boolean enabled = true;
    }

    @EqualsAndHashCode(callSuper = true)
    @Data
    public static class NeteaseApiConfig extends ApiConfig {
        private String cookie;
        private String quality = "exhigh"; // 默认音质：极高 (exhigh)
        private boolean enabled = true;
    }
}