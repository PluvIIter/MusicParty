package org.thornex.musicparty.dto;

public record ChatMessage(
        String id,          // UUID
        String userId,      // Token (用于区分是不是自己)
        String userName,    // 发送者名字
        String content,     // 消息内容
        long timestamp,     // 时间戳
        boolean isSystem    // 是否为系统消息 (可选，比如 xxx 进入房间)
) {}

