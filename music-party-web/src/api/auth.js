import client from './client';

export const authApi = {
    // 检查房间状态 (是否初始化/有密码)
    getStatus: () => client.get('/api/auth/status'),
    // 验证密码
    verify: (password) => client.post('/api/auth/verify', { password }),
    // 初始化/设置密码
    setup: (password) => client.post('/api/auth/setup', { password }),

    // 管理员指令
    adminReset: (password) => client.post('/api/admin/reset', { password }),
    adminSetPassword: (adminPassword, roomPassword) =>
        client.post('/api/admin/password', { adminPassword, roomPassword }),
};