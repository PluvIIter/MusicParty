package org.thornex.musicparty.dto;

public record PlayerEvent(
        String type,
        String action,    // 新增: 动作代码, 如 "SKIP", "PAUSE", "ADD"
        String userId,    // 修改: SessionID
        String payload    //  新增: 额外信息 (如歌曲名)
) {}
