package org.thornex.musicparty.dto;

/** queue-data.json 顶层 settings 的持久化快照。全部装箱类型，字段缺失时反序列化为 null。 */
public record SettingsSnapshot(
        PlayerSettings player,          // 播放相关（MusicPlayerService 运行时状态）
        String roomPassword,            // null=未初始化(恢复跳过) / ""=无密码 / 其它=密码
        Boolean streamEnabled,          // 直播推流开关
        PrivateDjSettings privateDj,    // 私人电台配置
        SystemConfigSettings systemConfig // 系统参数
) {
    public record PlayerSettings(
            String playMode,            // SEQUENTIAL / SHUFFLE / REPEAT_ONE
            Boolean fairShuffle,
            Boolean allowOfflineShuffle,
            Boolean voteSkipEnabled,
            Double voteSkipThreshold,
            Integer voteSkipWaitTime,
            Boolean pauseLocked,
            Boolean skipLocked,
            Boolean playModeLocked
    ) {}

    public record PrivateDjSettings(
            String mode,                // OFF / FM / DJ
            Boolean fillBlankEnabled,
            Boolean joinQueueEnabled,
            Boolean custodyEnabled
    ) {}

    public record SystemConfigSettings(
            Integer maxQueueSize, Integer maxHistorySize, Integer maxUserSongs,
            Integer maxPlaylistImportSize, Integer maxChatHistorySize,
            Long minChatIntervalMs,
            Boolean neteaseEnabled, Boolean bilibiliEnabled,
            Integer bilibiliMaxDurationMinutes
    ) {}
}
