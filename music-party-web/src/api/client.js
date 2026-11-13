import axios from 'axios';

/**
 * S-07 REST 房间保护：验证成功后服务端返回短期 roomToken，仅保存在内存中，
 * 之后对受保护路径（/api/search /api/music /api/playlist /api/user）自动带 X-Room-Token。
 * 旧服务端不返回 roomToken 时保持不带头（向后兼容）。
 */
let roomToken = null;
let roomPassword = null;
let refreshPromise = null;

export const setRoomToken = (token) => {
    roomToken = token || null;
};

export const getRoomToken = () => roomToken;

export const clearRoomToken = () => {
    roomToken = null;
    roomPassword = null;
};

const requestPath = (config) => {
    const url = new URL(config.url || '/', window.location.origin);
    return url.origin === window.location.origin ? url.pathname : '';
};
const protectedRequest = config => /^\/api\/(search|music|playlist|user)(\/|$)/.test(requestPath(config));

const client = axios.create({
    // 可以在这里配置 baseURL 或 timeout
    timeout: 10000,
});

// 请求拦截器：受保护 REST 路径带上房间凭证
client.interceptors.request.use(config => {
    if (roomToken && protectedRequest(config)) {
        config.headers = config.headers || {};
        config.headers['X-Room-Token'] = roomToken;
    }
    return config;
});

// 响应拦截器：提取 verify 返回的 roomToken；401 ROOM_TOKEN_INVALID 时清空并让上层重新验证
client.interceptors.response.use(
    (res) => res.data,
    (error) => Promise.reject(error)
);

export default client;
