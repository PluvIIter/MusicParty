package org.thornex.musicparty.dto;

import org.thornex.musicparty.enums.MessageType;

public record ChatMessage(
        String id,          // UUID
        String userId,      // Token (用于区分是不是自己)
        String userName,    // 发送者名字
        String content,     // 消息内容
        long timestamp,     // 时间戳
        MessageType type    // 消息类型
) {}

