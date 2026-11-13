import { Client, TickerStrategy } from '@stomp/stompjs';
import { WS_DEST } from '../constants/api';
import { createCompactReceiver } from './compactSync';

class SocketService {
    constructor() {
        this.client = null;
        this.connected = false;
        this.stompConfig = null;
        this.lastMessageAt = 0;
        this.lastReconnectAt = 0;
        this.callbacks = null;
        this.subscriptionHandles = new Map();
        this.compact = null;
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
        this.callbacks = callbacks;
        this.compact = createCompactReceiver({
            apply: callbacks.onState,
            requestSnapshot: () => this.send(WS_DEST.COMPACT_RESYNC),
        });

        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const brokerURL = `${protocol}//${window.location.host}/ws`;

        this.client = new Client({
            brokerURL,
            connectHeaders: authHeaders,
            heartbeatIncoming: 10000,
            heartbeatOutgoing: 10000,
            heartbeatStrategy: typeof Worker === 'undefined' ? TickerStrategy.Interval : TickerStrategy.Worker,
            connectionTimeout: 10000,
            discardWebsocketOnCommFailure: true,
            reconnectDelay: 2000,

            onConnect: (frame) => {
                this.connected = true;
                this.lastMessageAt = Date.now();
                this.compact.reset();
                this.subscriptionHandles.clear();

                const compactHandler = (frame) => this.compact.receive(frame);
                const allSubscriptions = {
                    [WS_DEST.TOPIC_COMPACT]: compactHandler,
                    [WS_DEST.USER_COMPACT]: compactHandler,
                    ...subscriptions,
                    [WS_DEST.APP_COMPACT]: compactHandler,
                };
                Object.entries(allSubscriptions).forEach(([topic, handler]) => {
                    const subscription = this.client.subscribe(topic, (message) => {
                        // 心跳/半开连接检测：任何入站消息都算作链路活动的证据
                        this.lastMessageAt = Date.now();
                        const body = JSON.parse(message.body);
                        handler(body);
                    });
                    this.subscriptionHandles.set(topic, subscription);
                });

                // 2. 触发连接成功回调
                if (callbacks.onConnect) callbacks.onConnect(frame);
            },

            // 监听非正常关闭 (如网络中断、服务器重启)
            onWebSocketClose: () => {
                console.warn('WebSocket connection closed.');
                this.connected = false;
                this.compact.reset();
                // 触发断开回调，让 Store 感知状态变化
                if (callbacks.onDisconnect) callbacks.onDisconnect();
            },

            onDisconnect: () => {
                this.connected = false;
                this.compact.reset();
                if (callbacks.onDisconnect) callbacks.onDisconnect();
            },

            onStompError: (frame) => {
                console.error('STOMP Error:', frame.body);
                if (callbacks.onStompError) callbacks.onStompError(frame);
            },
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
            useToast().warning('连接已断开，操作未发送');
            return Promise.resolve({ok:false, confirmed:false, code:'DISCONNECTED'});
        }
    }

    /**
     * 强制重连 (用于网络恢复或从后台切回时)
     */
    forceReconnect() {
        if (!this.client) return;
        if (!this.client.active) {
            console.log('Force reconnecting socket...');
            this.client.activate();
        } else if (
            this.client.connected &&
            Date.now() - this.lastMessageAt > 20000 &&
            Date.now() - this.lastReconnectAt > 10000
        ) {
            // active means reconnect is enabled, not that the existing transport is healthy.
            // A suspended phone can return with a half-open socket still marked connected.
            this.lastReconnectAt = Date.now();
            this.connected = false;
            this.callbacks?.onDisconnect?.();
            this.client.forceDisconnect();
        }
    }

    /**
     * 断开连接
     */
    disconnect() {
        this.clearPending();
        if (this.client) {
            this.client.deactivate();
            this.client = null;
            this.connected = false;
        }
    }
}

// 导出单例
export const socketService = new SocketService();
