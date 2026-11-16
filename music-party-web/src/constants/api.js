// WebSocket 目的地
export const WS_DEST = {
    QUEUE_CLEAR_MINE: '/app/queue/clear-mine',
    USER_PLAYLIST_CLEAR: '/user/queue/playlist/clear',
    // 发送指令 (Publish)
    CHAT_SEND: '/app/chat',
    CHAT_HISTORY_FETCH: '/app/chat/history/fetch',
    PLAYER_NEXT: '/app/control/next',
    PLAYER_PAUSE: '/app/control/toggle-pause',
    PLAYER_MODE: '/app/control/play-mode',
    MEMBER_SHUFFLE: '/app/queue/member-shuffle',
    QUEUE_REORDER: '/app/queue/reorder',
    PLAYER_LIKE: '/app/control/like',
    /** 拖动进度条（房间级 seek，受管理面板 allowSeek 开关约束）。 */
    PLAYER_SEEK: '/app/control/seek',
    ENQUEUE: '/app/enqueue',
    ENQUEUE_CONTAINER: '/app/enqueue/container',
    USER_CONTAINER_IMPORT: '/user/queue/container/import',
    QUEUE_TOP: '/app/queue/top',
    QUEUE_REMOVE: '/app/queue/remove',
    QUEUE_CLAIM: '/app/queue/claim',
    USER_RENAME: '/app/user/rename',
    COMPACT_RESYNC: '/app/player/compact-resync',

    // 订阅频道 (Subscribe)
    TOPIC_EVENTS: '/topic/player/events',
    TOPIC_COMPACT: '/topic/player/compact',
    USER_COMPACT: '/user/queue/player/compact',
    APP_COMPACT: '/app/player/compact',
    TOPIC_CHAT: '/topic/chat',

    // 个人频道
    USER_ME: '/app/user/me',
    USER_ME_UPDATE: '/user/queue/me',
    APP_CHAT_HISTORY: '/app/chat/history',
    USER_CHAT_HISTORY: '/user/queue/chat/history',
    USER_EVENTS: '/user/queue/events',
    USER_PLAYLIST_CLAIM: '/user/queue/playlist/claim',
    USER_PRIVATE_CHAT: '/user/queue/chat/private',
};
