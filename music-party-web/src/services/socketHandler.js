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
    const userName = event.userId === 'SYSTEM' ? '系统' : userStore.resolveName(event.userId);

    if (event.action === 'LIKE') {
        window.dispatchEvent(new CustomEvent('player:like', { detail: { userId: event.userId } }));
    }

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
        error('该名称已被占用，请更换。');
        userStore.showNameModal = true;
        return;
    }

    // 2. 构建通知文案
    const actionMap = {
        // 播放控制
        'PLAY': (u) => `${u} 开始了播放`,
        'PAUSE': (u) => `${u} 暂停了播放`,
        'RESUME': (u) => `${u} 继续了播放`,
        'SKIP': (u) => `${u} 切到了下一首`,

        // 队列操作
        'ADD': (u, p) => `${u} 添加了: ${p}`,
        'REMOVE': (u, p) => `${u} 移除了: ${p}`,
        'TOP': (u, p) => `${u} 置顶了: ${p}`,
        'IMPORT_PLAYLIST': (u, p) => `${u} 导入了歌单 (${p}首)`,
        'SHUFFLE_ON': (u) => `${u} 开启了随机播放`,
        'SHUFFLE_OFF': (u) => `${u} 关闭了随机播放`,

        // 交互
        'LIKE': (u) => `${u} 觉得很赞！`,

        // 系统级
        'RESET': () => `系统已被重置`,
        'ERROR_LOAD': (u, p) => `加载失败: ${p || '未知错误'} (已跳过)`
    };

    let msgText = '';
    const generator = actionMap[event.action];

    if (generator) {
        msgText = generator(userName, event.payload);
    } else {
        // 兜底：未知的 Action
        msgText = `${userName} 执行了操作: ${event.action}`;
        if (event.payload) msgText += ` (${event.payload})`;
    }

    // 如果是 ERROR_LOAD，强制类型为 error，否则使用后端传来的 type (INFO/WARN/SUCCESS)
    let type = event.type ? event.type.toLowerCase() : 'info';
    if (event.action === 'ERROR_LOAD') type = 'error';

    show({
        title: event.action === 'ERROR_LOAD' ? 'PLAYBACK ERROR' : event.action,
        message: msgText,
        type: type,
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