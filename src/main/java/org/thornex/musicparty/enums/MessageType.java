package org.thornex.musicparty.enums;

public enum MessageType {
    CHAT,   // 用户聊天
    SYSTEM, // 系统日志 (切歌、暂停等)
    LIKE,   // 点赞
    PLAY_START // 开始播放 (出现在聊天和系统栏)
}
