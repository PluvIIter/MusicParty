import axios from 'axios';

const client = axios.create({
    // 可以在这里配置 baseURL 或 timeout
    timeout: 10000
});

// 响应拦截器：可以在这里统一处理 401/403 等错误
client.interceptors.response.use(
    res => res.data,
    error => Promise.reject(error)
);

export default client;