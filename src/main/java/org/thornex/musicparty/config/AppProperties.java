package org.thornex.musicparty.config;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.music-api")
@Data
public class AppProperties {
    private NeteaseApiConfig  netease  = new NeteaseApiConfig();
    private BilibiliApiConfig bilibili = new BilibiliApiConfig();
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
    private StreamConfig stream = new StreamConfig();
    private PrivateDjConfig privateDj = new PrivateDjConfig();

    /** 私人电台/私人DJ 模块配置（仅运行时生效） */
    @Data
    public static class PrivateDjConfig {
        private boolean masterEnabled = false;    // 总开关（需已配置网易云 cookie 才能开启）
        private String mode = "FM";               // "FM" | "DJ"
        private boolean fillBlankEnabled = false; // 填充空白
        private boolean joinQueueEnabled = false; // 加入队列
        private boolean custodyEnabled = false;   // 播放托管
    }

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
        private long syncBroadcastIntervalMs = 5000; // 周期状态广播间隔（ms），修复移动端后台同步漂移
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

    /**
     * 直播流（radio）模块配置
     */
    @Data
    public static class StreamConfig {
        /** 最大同时连接的收听者数量（按连接数，非唯一 IP） */
        private int maxClients = 100;
        /** 每个收听者的缓冲队列容量（块数），chunkSizeBytes * bufferChunks ≈ 缓冲时长 */
        private int bufferChunks = 32;
        /** ffmpeg 输出的分块大小（字节），默认 16KB */
        private int chunkSizeBytes = 16384;
        /** seek 判定阈值（毫秒）：与实时进度的漂移超过该值才重启转码 */
        private long seekThresholdMs = 3000;
        /** ResponseBodyEmitter 超时（毫秒），默认 24h。Tomcat 默认 async 超时仅 30s，必须显式设大 */
        private long emitterTimeoutMs = 24 * 60 * 60 * 1000L;
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
        /** B站视频时长上限（分钟），超过则前端标记为不可播放 */
        private int maxDurationMinutes = 10;
    }

    @EqualsAndHashCode(callSuper = true)
    @Data
    public static class NeteaseApiConfig extends ApiConfig {
        private String cookie;
        private String quality = "exhigh"; // 默认音质：极高 (exhigh)
        private boolean enabled = true;
    }
}