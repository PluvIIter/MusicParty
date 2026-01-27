import { Client } from '@stomp/stompjs';

class SocketService {
    constructor() {
        this.client = null;
        this.connected = false;
        this.stompConfig = null;
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

            onConnect: (frame) => {
                this.connected = true;

                // 1. 注册所有订阅
                Object.entries(subscriptions).forEach(([topic, handler]) => {
                    this.client.subscribe(topic, (message) => {
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