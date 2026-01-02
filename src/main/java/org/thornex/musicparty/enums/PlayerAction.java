package org.thornex.musicparty.enums;

/**
 * 定义播放器或队列的所有操作类型
 */
public enum PlayerAction {
    // 播放控制
    PLAY,
    PAUSE,
    RESUME,
    SKIP,

    // 队列操作
    ADD,
    REMOVE,
    TOP,
    SHUFFLE_ON,
    SHUFFLE_OFF,
    IMPORT_PLAYLIST,

    // 交互
    LIKE,

    // 系统级
    RESET,
    ERROR_LOAD
}