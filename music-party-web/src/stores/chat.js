import { defineStore } from 'pinia';
import { ref } from 'vue';
import { useUserStore } from './user';

export const useChatStore = defineStore('chat', () => {
    const messages = ref([]);
    const unreadCount = ref(0);
    const isOpen = ref(false); // 聊天窗是否打开

    const userStore = useUserStore();
    const LIMIT = 100;

    // 添加消息
    const addMessage = (msg) => {
        messages.value.push(msg);

        // 限制长度
        if (messages.value.length > LIMIT) {
            messages.value.shift();
        }

        // 如果聊天窗没打开，且不是自己发的消息，增加未读数
        if (!isOpen.value && msg.userId !== userStore.userToken) {
            unreadCount.value++;
        }
    };

    // 切换窗口
    const toggleChat = () => {
        isOpen.value = !isOpen.value;
        if (isOpen.value) {
            unreadCount.value = 0;
        }
    };

    const setHistory = (history) => {
        messages.value = history;
        // 刚连上时，未读数不应该增加，或者设为 0
        unreadCount.value = 0;
    };


    // 发送消息 (调用 PlayerStore 里的 stompClient 发送，或者单独封装)
    // 为了简单，我们直接在组件里调用 playerStore.sendMessage 或者这里引入 playerStore
    // 但为了解耦，我们这里只负责状态，发送逻辑在组件或 PlayerStore 中做

    return {
        messages,
        unreadCount,
        isOpen,
        addMessage,
        toggleChat,
        setHistory
    };
});