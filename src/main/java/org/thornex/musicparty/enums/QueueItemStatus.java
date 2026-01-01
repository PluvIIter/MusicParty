package org.thornex.musicparty.enums;

/**
 * 明确队列中单曲的状态
 */
public enum QueueItemStatus {
    PENDING,        // 初始状态（如 B站视频需解析/下载）
    DOWNLOADING,    // 正在下载缓存 (对应 LocalCacheService)
    READY,          // 就绪，随时可播 (网易云默认此状态，B站下载完变此状态)
    PLAYING,        // 当前正在播放
    FAILED          // 解析或下载失败
}