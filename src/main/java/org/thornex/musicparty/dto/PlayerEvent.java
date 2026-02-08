package org.thornex.musicparty.dto;

public record PlayerEvent(
        String type,
        String action,    // 动作代码, 如 "SKIP", "PAUSE", "ADD"
        String userId,    // 发起者 ID
        String message,   // 新增: 格式化好的显示文本
        String payload    // 额外信息 (如歌曲名)
) {}

