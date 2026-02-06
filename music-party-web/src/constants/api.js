// WebSocket 目的地
export const WS_DEST = {
    // 发送指令 (Publish)
    CHAT_SEND: '/app/chat',
    CHAT_HISTORY_FETCH: '/app/chat/history/fetch',
    PLAYER_NEXT: '/app/control/next',
    PLAYER_PAUSE: '/app/control/toggle-pause',
    PLAYER_SHUFFLE: '/app/control/toggle-shuffle',
    PLAYER_LIKE: '/app/control/like',
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
    USER_ME_UPDATE: '/user/queue/me',
    APP_CHAT_HISTORY: '/app/chat/history',
    USER_STATE: '/user/queue/player/state',
    USER_CHAT_HISTORY: '/user/queue/chat/history',
    USER_EVENTS: '/user/queue/events',
    USER_PRIVATE_CHAT: '/user/queue/chat/private'
};