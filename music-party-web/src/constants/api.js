// WebSocket 目的地
export const WS_DEST = {
    // 发送指令 (Publish)
    CHAT_SEND: '/app/chat',
    PLAYER_NEXT: '/app/control/next',
    PLAYER_PAUSE: '/app/control/toggle-pause',
    PLAYER_SHUFFLE: '/app/control/toggle-shuffle',
    ENQUEUE: '/app/enqueue',
    ENQUEUE_PLAYLIST: '/app/enqueue/playlist',
    QUEUE_TOP: '/app/queue/top',
    QUEUE_REMOVE: '/app/queue/remove',
    USER_BIND: '/app/user/bind',
    USER_RENAME: '/app/user/rename',
    RESYNC: '/app/player/resync',

    // 订阅频道 (Subscribe)
    TOPIC_EVENTS: '/topic/player/events',
    TOPIC_STATE: '/topic/player/state',
    TOPIC_QUEUE: '/topic/player/queue',
    TOPIC_USERS: '/topic/users/online',
    TOPIC_CHAT: '/topic/chat',

    // 个人频道
    USER_ME: '/app/user/me',
    CHAT_HISTORY: '/app/chat/history',
    USER_STATE: '/user/queue/player/state'
};