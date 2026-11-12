export const ADMIN_CONFIG_PAYLOAD_FIELDS = new Set([
    'maxQueueSize',
    'maxHistorySize',
    'maxUserSongs',
    'maxPlaylistImportSize',
    'maxChatHistorySize',
    'minChatIntervalMs',
    'maxChatMessageLength',
    'sources',
    'bilibiliMaxDurationMinutes',
    'voteSkipEnabled',
    'voteSkipThreshold',
    'voteSkipWaitTime',
    'retainPlaylist',
    'allowSeek',
]);

export const toAdminConfigPayload = (config) =>
    Object.fromEntries(
        Object.entries(config).filter(([key, value]) => value !== undefined && ADMIN_CONFIG_PAYLOAD_FIELDS.has(key))
    );
