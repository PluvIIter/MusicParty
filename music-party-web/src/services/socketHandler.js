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

    if (event.action === 'RENAME_FAILED' || (event.type === 'ERROR' && event.message && (event.message.includes('taken') || event.message.includes('占用')))) {
        error(event.message || '该名称已被占用，请更换。');
        userStore.showNameModal = true;
        return;
    }

    // 过滤掉用户进入/离开、以及歌曲开始播放的系统内部通知，避免弹窗干扰
    if (event.action === 'USER_JOIN' || event.action === 'USER_LEAVE' || event.action === 'PLAY_START') {
        return;
    }

    // 2. 构建通知文案
    const actionMap = {
        // 播放控制
        'PLAY': (u) => `${u} 开始了播放`,
        'PLAY_START': (u, p) => `正在播放: ${p}`,
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
        'ERROR_LOAD': (u, p) => `加载失败: ${p || '未知错误'} (跳过)`
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
        // 1. 状态同步
        [WS_DEST.TOPIC_STATE]: (state) => playerStore.syncState(state),
        [WS_DEST.USER_STATE]: (state) => playerStore.syncState(state),

        // 2. 用户列表
        [WS_DEST.TOPIC_USERS]: (users) => userStore.setOnlineUsers(users),

        // 3. 队列更新
        [WS_DEST.TOPIC_QUEUE]: (data) => { playerStore.queue = data; },

        // 4. 事件通知 (Toast)
        [WS_DEST.TOPIC_EVENTS]: handleGameEvent,
        [WS_DEST.USER_EVENTS]: handleGameEvent,

        // 5. 聊天相关
        [WS_DEST.TOPIC_CHAT]: (msg) => chatStore.addMessage(msg),

        // 初始历史记录
        [WS_DEST.APP_CHAT_HISTORY]: (history) => chatStore.setHistory(history),

        // 分页历史记录回调
        [WS_DEST.USER_CHAT_HISTORY]: (moreMessages) => chatStore.prependHistory(moreMessages)
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

        // STOMP 协议层错误 (如密码错误、Token失效、服务器内部错误等)
        onStompError: (frame) => {
            console.error('STOMP Error:', frame);

            // 无论是密码错误，还是其他未知的协议级错误，都强制刷新以重置状态
            const isAuthError = frame.body && frame.body.includes('INVALID_ROOM_PASSWORD');

            if (isAuthError) {
                userStore.resetAuthentication();
            }

            // 强制刷新页面 (STOMP ERROR 帧通常意味着连接已不可用)
            window.location.reload();
        }
    };
};

// 为了兼容旧代码命名，导出这个别名
const handleEventMessage = handleGameEvent;