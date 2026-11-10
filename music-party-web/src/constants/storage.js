export const STORAGE_KEYS = {
    TOKEN: 'mp_user_token',
    USERNAME: 'mp_username',
    BINDINGS: 'mp_bindings',
    ROOM_PASSWORD: 'mp_room_password',
    VOLUME: 'mp_volume',
    ADMIN_PASSWORD: 'mp_admin_password',
    LIKED_SONGS: 'mp_liked_songs', // 点赞歌曲列表（仅本地缓存，不上报服务器）
    AUTO_LITE_MODE: 'mp_auto_lite_mode', // 精简模式偏好
    PWA_HINT_SHOWN: 'mp_pwa_hint_shown', // PWA 安装提示是否已展示
    TUTORIAL_DONE: 'mp_tutorial_done_v1', // 新手引导是否已完成
    THEME: 'mp_theme', // 外观偏好：light | dark（用户本地，不上报）
};
