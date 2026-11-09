/**
 * 后端 AdminConfigUpdateRequest 接受的字段集合。
 * 与 src/main/java/org/thornex/musicparty/dto/AdminConfigUpdateRequest.java 保持一致。
 */
export const ADMIN_CONFIG_PAYLOAD_FIELDS = new Set([
    'maxSize',
    'historySize',
    'maxUserSongs',
    'maxPlaylistImportSize',
    'maxChatHistorySize',
    'minChatIntervalMs',
    'maxChatMessageLength',
    'neteaseEnabled',
    'bilibiliEnabled',
    'bilibiliMaxDurationMinutes',
    'voteSkipEnabled',
    'voteSkipThreshold',
    'voteSkipWaitTime',
    // 队列策略开关（#23 引入；allowPlaylistClaim 由 PlaylistClaimSettingsController 专用端点处理，不走这里）
    'retainPlaylist',
]);

/**
 * 前端 config 字段名 → 后端 DTO 字段名。
 *
 * 这两个是历史遗留的不一致：前端叫 maxQueueSize / maxHistorySize，
 * 后端 DTO 叫 maxSize / historySize。Spring Boot 默认不因未知字段报错，
 * 所以此前这两个设置被静默丢弃。
 */
const FIELD_RENAMES = {
    maxQueueSize: 'maxSize',
    maxHistorySize: 'historySize',
};

/**
 * 把前端 config 对象转成后端认识的 payload。
 * 重命名映射字段，丢弃不在 DTO 白名单里的字段（privateDj、
 * neteaseCookieConfigured 等各有专用端点）。
 *
 * @param {object} config
 * @returns {object} 可直接作为 POST /api/admin/config/update 的 body
 */
export const toAdminConfigPayload = (config) => {
    const payload = {};
    for (const [key, value] of Object.entries(config)) {
        if (value === undefined) continue;
        const targetKey = FIELD_RENAMES[key] ?? key;
        if (!ADMIN_CONFIG_PAYLOAD_FIELDS.has(targetKey)) continue;
        payload[targetKey] = value;
    }
    return payload;
};
