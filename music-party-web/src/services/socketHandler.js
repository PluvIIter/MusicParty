// src/services/socketHandler.js

import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import { useChatStore } from '../stores/chat';
import { useToast } from '../composables/useToast';
import {socketService} from "./socket.js";
import {WS_DEST} from "../constants/api.js";

/**
 * 处理游戏/播放器事件通知 (Toast)
 * 这里集中管理所有的业务通知文案
 */
function handleGameEvent(event) {
    const userStore = useUserStore();
    const chatStore = useChatStore();
    const { show, error } = useToast();

    // 1. 特殊指令处理
    if (event.action === 'RESET') {
        chatStore.messages = []; // 清空聊天
    }

    if (event.action === 'PASSWORD_CHANGED') {
        error('房间密码已更改，请重新验证');
        setTimeout(() => {
            userStore.resetAuthentication();
            window.location.reload();
        }, 1500);
        return;
    }

    if (event.type === 'ERROR' && event.message && event.message.includes('taken')) {
        error('该代号已被占用，请更换。');
        userStore.showNameModal = true;
        return;
    }

    // 2. 构建通知文案
    const userName = userStore.resolveName(event.userId);
    let msgText = `${userName} 执行了操作`;

    // 辅助函数：判断 payload 是否为真值 (兼容 boolean 和 string)
    const isTrue = (val) => val === true || val === 'true' || val === 'ON' || val === 'on';

    const actionMap = {
        'SKIP': `${userName} 切到了下一首`,
        'PAUSE': `${userName} 暂停了播放`,
        'RESUME': `${userName} 继续了播放`,
        'ADD': `${userName} 添加了: ${event.payload}`,
        'IMPORT': `${userName} 导入了歌单 (${event.payload}首)`,
        'TOP': `${userName} 置顶了: ${event.payload}`,
        'REMOVE': `${userName} 移除了: ${event.payload}`,
        'SHUFFLE_ON': `${userName} 开启了随机播放`,
        'SHUFFLE_OFF': `${userName} 关闭了随机播放`,
        'RESET': '系统已被重置',
        'LOAD_FAILED': `资源获取失败: ${event.payload} (自动跳过)`
    };

    if (actionMap[event.action]) {
        msgText = actionMap[event.action];
    }

    // 3. 显示 Toast
    show({
        title: event.action,
        message: msgText,
        type: event.type ? event.type.toLowerCase() : 'info',
        duration: 3000
    });
}

/**
 * 创建并返回 Socket 订阅配置
 * @returns {Object} 订阅路径 -> 回调函数 的映射
 */
export const createSocketSubscriptions = () => {
    const playerStore = usePlayerStore();
    const userStore = useUserStore();
    const chatStore = useChatStore();

    return {
        // 1. 状态同步：直接调用 Store 的 Action 更新数据
        '/topic/player/state': (state) => {
            playerStore.syncState(state);
        },
        '/user/queue/player/state': (state) => {
            playerStore.syncState(state);
        },

        // 2. 用户列表更新
        '/topic/users/online': (users) => {
            userStore.setOnlineUsers(users);
        },

        // 3. 队列更新 (通常 state 里也包含，这里可能是单独推送)
        '/topic/player/queue': (data) => {
            playerStore.queue = data;
        },

        // 4. 聊天消息
        '/topic/chat': (msg) => {
            chatStore.addMessage(msg);
        },
        '/app/chat/history': (history) => {
            chatStore.setHistory(history);
        },

        // 5. 事件通知 (抽离出的逻辑)
        '/topic/player/events': handleEventMessage
    };
};

/**
 * [新增] 创建 Socket 生命周期回调
 * 包含：连接成功处理、断连处理、错误处理
 */
export const createSocketCallbacks = () => {
    const playerStore = usePlayerStore();
    const userStore = useUserStore();

    return {
        // 连接成功
        onConnect: () => {
            playerStore.connected = true;
            // 发起同步
            setTimeout(() => {
                socketService.send(WS_DEST.RESYNC);
            }, 300);
            // 恢复绑定
            Object.entries(userStore.bindings).forEach(([platform, id]) => {
                if (id) playerStore.bindAccount(platform, id);
            });
        },

        // 连接断开 (含异常断开)
        onDisconnect: () => {
            playerStore.connected = false;
        },

        // STOMP 协议层错误 (如密码错误)
        onStompError: (frame) => {
            if (frame.body === 'INVALID_ROOM_PASSWORD') {
                console.error('Auth Failed: Invalid Password');
                userStore.resetAuthentication();
                // 强制刷新页面回到登录状态
                window.location.reload();
            } else {
                console.error('STOMP Error:', frame);
            }
        }
    };
};

// 为了兼容旧代码命名，导出这个别名
const handleEventMessage = handleGameEvent;