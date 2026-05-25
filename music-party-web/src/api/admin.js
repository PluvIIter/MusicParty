import client from './client';

/**
 * 管理员后台专用接口封装
 */
export const adminApi = {
    // 验证密码
    verify: (password) => client.post('/api/admin/verify', { password }),

    // 锁定控制 (PAUSE/SKIP/SHUFFLE/ALL)
    setLock: (adminPwd, type, locked) => client.post('/api/admin/lock', { type, locked }, {
        headers: { 'X-Admin-Password': adminPwd }
    }),

    // 强制播放器操作 (PAUSE/SKIP/SHUFFLE)
    playerAction: (adminPwd, action) => client.post('/api/admin/player/action', { action }, {
        headers: { 'X-Admin-Password': adminPwd }
    }),

    // 修改房间密码 (password为空即为开启房间)
    setRoomPassword: (adminPwd, password) => client.post('/api/admin/room/password', { password }, {
        headers: { 'X-Admin-Password': adminPwd }
    }),

    // 清理数据 (QUEUE/CHAT)
    clearData: (adminPwd, target) => client.post('/api/admin/room/clear', { target }, {
        headers: { 'X-Admin-Password': adminPwd }
    }),

    // 系统重置
    resetSystem: (adminPwd) => client.post('/api/admin/system/reset', {}, {
        headers: { 'X-Admin-Password': adminPwd }
    }),

    // 更新平台 Cookie
    setCookie: (adminPwd, platform, value) => client.post('/api/admin/config/cookie', { platform, value }, {
        headers: { 'X-Admin-Password': adminPwd }
    }),

    // 直播流控制
    setStream: (adminPwd, enabled) => client.post('/api/admin/room/stream', { enabled }, {
        headers: { 'X-Admin-Password': adminPwd }
    }),

    // 更新系统配置
    updateConfig: (adminPwd, config) => client.post('/api/admin/config/update', config, {
        headers: { 'X-Admin-Password': adminPwd }
    })
};
