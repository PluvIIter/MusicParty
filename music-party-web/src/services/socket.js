import { Client } from '@stomp/stompjs';
import HeartbeatWorker from './heartbeat.worker?worker';

class SocketService {
    constructor() {
        this.client = null;
        this.connected = false;
        this.stompConfig = null;
        this.lastActivity = Date.now();
        this.worker = null;
        this.workerCallbacks = new Map();
        this.nextTimerId = 0;

        // 初始化 Worker (如果浏览器支持)
        if (typeof Worker !== 'undefined') {
            try {
                this.worker = new HeartbeatWorker();
                this.worker.onmessage = (e) => {
                    const { id, type } = e.data;
                    const cb = this.workerCallbacks.get(id);
                    if (cb) {
                        cb();
                        if (type === 'timeout') this.workerCallbacks.delete(id);
                    }
                };
                console.log('[Socket] Heartbeat Worker initialized');
            } catch (e) {
                console.error('[Socket] Failed to initialize Heartbeat Worker:', e);
            }
        }
    }

    _customSetInterval(cb, interval) {
        if (!this.worker) return setInterval(cb, interval);
        const id = ++this.nextTimerId;
        this.workerCallbacks.set(id, cb);
        this.worker.postMessage({ type: 'setInterval', id, interval });
        return id;
    }

    _customClearInterval(id) {
        if (!this.worker) return clearInterval(id);
        this.workerCallbacks.delete(id);
        this.worker.postMessage({ type: 'clearInterval', id });
    }

    _customSetTimeout(cb, interval) {
        if (!this.worker) return setTimeout(cb, interval);
        const id = ++this.nextTimerId;
        this.workerCallbacks.set(id, cb);
        this.worker.postMessage({ type: 'setTimeout', id, interval });
        return id;
    }

    _customClearTimeout(id) {
        if (!this.worker) return clearTimeout(id);
        this.workerCallbacks.delete(id);
        this.worker.postMessage({ type: 'clearTimeout', id });
    }

    /**
     * 初始化连接
     * @param {Object} authHeaders - { 'user-name':..., 'user-token':..., 'room-password':... }
     * @param {Object} callbacks - 回调函数集合
     * @param {Function} callbacks.onConnect - 连接成功
     * @param {Function} callbacks.onDisconnect - 连接断开
     * @param {Function} callbacks.onStompError - STOMP 错误 (如密码错误)
     * @param {Object} subscriptions - 订阅配置 { topic: callbackFn }
     */
    connect(authHeaders, callbacks, subscriptions) {
        // 避免重复连接
        if (this.client && this.client.active) return;

        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const brokerURL = `${protocol}//${window.location.host}/ws`;

        this.client = new Client({
            brokerURL,
            connectHeaders: authHeaders,
            heartbeatIncoming: 10000,
            heartbeatOutgoing: 10000,
            reconnectDelay: 2000,

            // 注入自定义定时器以绕过移动端后台节流
            setInterval: this._customSetInterval.bind(this),
            clearInterval: this._customClearInterval.bind(this),
            setTimeout: this._customSetTimeout.bind(this),
            clearTimeout: this._customClearTimeout.bind(this),

            onConnect: (frame) => {
                this.connected = true;
                this.lastActivity = Date.now();

                // 1. 注册所有订阅
                Object.entries(subscriptions).forEach(([topic, handler]) => {
                    this.client.subscribe(topic, (message) => {
                        this.lastActivity = Date.now();
                        const body = JSON.parse(message.body);
                        handler(body);
                    });
                });

                // 2. 触发连接成功回调
                if (callbacks.onConnect) callbacks.onConnect(frame);
            },

            // 监听非正常关闭 (如网络中断、服务器重启)
            onWebSocketClose: () => {
                console.warn('WebSocket connection closed.');
                this.connected = false;
                // 触发断开回调，让 Store 感知状态变化
                if (callbacks.onDisconnect) callbacks.onDisconnect();
            },

            onDisconnect: () => {
                this.connected = false;
                if (callbacks.onDisconnect) callbacks.onDisconnect();
            },

            onStompError: (frame) => {
                console.error('STOMP Error:', frame.body);
                if (callbacks.onStompError) callbacks.onStompError(frame);
            }
        });

        this.client.activate();
    }

    /**
     * 发送指令 (通用)
     * @param {string} destination - 目标地址 (来自 WS_DEST)
     * @param {Object} body - 消息体
     */
    send(destination, body = {}) {
        if (this.client && this.connected) {
            this.client.publish({ destination, body: JSON.stringify(body) });
        } else {
            console.warn('Socket not connected, cannot send:', destination);
        }
    }

    /**
     * 强制重连 (用于网络恢复或从后台切回时)
     * 改进：即使 active 为 true，如果很久没收到消息也会重连
     */
    forceReconnect() {
        const now = Date.now();
        const timeSinceLastActivity = now - this.lastActivity;
        
        // 如果超过 25秒 没消息 (预期心跳是10秒)，或者 client 不活跃，则强制重连
        if (!this.client || !this.client.active || timeSinceLastActivity > 25000) {
            console.log(`[Socket] Force reconnecting... (Active: ${this.client?.active}, Last Activity: ${timeSinceLastActivity}ms ago)`);
            
            if (this.client) {
                this.client.deactivate();
            }
            
            // 延迟一点点确保之前的连接已关闭
            setTimeout(() => {
                if (this.client) {
                    this.client.activate();
                }
            }, 500);
        }
    }

    /**
     * 断开连接
     */
    disconnect() {
        if (this.client) {
            this.client.deactivate();
            this.client = null;
            this.connected = false;
        }
    }
}

// 导出单例
export const socketService = new SocketService();