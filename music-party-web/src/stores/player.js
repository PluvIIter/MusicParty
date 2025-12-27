// File Path: music-party-web\src\stores\player.js

import { defineStore } from 'pinia';
import { ref, watch } from 'vue';
import { Client } from '@stomp/stompjs';
import { useUserStore } from './user';
import { useToast } from '../composables/useToast';
import axios from 'axios';

export const usePlayerStore = defineStore('player', () => {
    // ... (状态变量不变)
    const userStore = useUserStore();
    const { show } = useToast();
    const nowPlaying = ref(null);
    const queue = ref([]);
    const isPaused = ref(false);
    const pauseTimeMillis = ref(0);
    const isShuffle = ref(false);
    const serverTimeOffset = ref(0);
    const lyricText = ref('');
    const stompClient = ref(null);
    const connected = ref(false);

    // ... (getCurrentProgress 不变)
    const getCurrentProgress = () => {
        if (!nowPlaying.value) return 0;
        const effectiveStartTime = nowPlaying.value.startTimeMillis;
        if (isPaused.value) {
            if (pauseTimeMillis.value > 0) {
                return Math.max(0, pauseTimeMillis.value - effectiveStartTime);
            }
            return 0;
        } else {
            const currentServerTime = Date.now() + serverTimeOffset.value;
            return Math.max(0, currentServerTime - effectiveStartTime);
        }
    };

    // 🟢 辅助函数：根据后端消息内容推断合适的标题
    // 后端消息格式如："ThorNex 切到了下一首", "ThorNex 添加了: SongName"
    const deriveTitle = (msg) => {
        if (msg.includes("切到了")) return "TRACK SWITCHED";
        if (msg.includes("添加了")) return "ADDED TO QUEUE";
        if (msg.includes("导入了")) return "PLAYLIST IMPORT";
        if (msg.includes("暂停了")) return "PLAYER PAUSED";
        if (msg.includes("继续了")) return "PLAYER RESUMED";
        if (msg.includes("随机播放")) return "SHUFFLE MODE";
        if (msg.includes("置顶了")) return "PRIORITY UPDATE";
        if (msg.includes("移除了")) return "QUEUE REMOVAL";
        if (msg.includes("重置")) return "SYSTEM ALERT";
        return "SYSTEM NOTICE";
    };

    const connect = () => {
        const savedName = localStorage.getItem('mp_username') || 'Guest';

        const client = new Client({
            brokerURL: `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/ws`,
            connectHeaders: {
                'user-name': savedName
            },
            onConnect: (frame) => {
                connected.value = true;

                client.subscribe('/app/user/me', (message) => {
                    const me = JSON.parse(message.body);
                    const needsSync = userStore.initUser(me.sessionId, me.name);
                    if (needsSync) {
                        renameUser(userStore.currentUser.name);
                    }
                });

                // 🟢 核心修改：优化 Toast 显示逻辑
                client.subscribe('/topic/player/events', (message) => {
                    const event = JSON.parse(message.body);
                    // event 结构: { type: "SUCCESS"|"INFO"|"ERROR", message: "UserX 做了什么...", user: "UserX" }

                    show({
                        // 1. 标题：根据内容推断操作类型（全大写，更有工业感）
                        title: deriveTitle(event.message),
                        // 2. 内容：保持后端发来的完整描述（包含用户名）
                        message: event.message,
                        // 3. 类型：转换为小写适配组件
                        type: event.type.toLowerCase(),
                        duration: 3000
                    });
                });

                client.subscribe('/topic/player/state', (message) => {
                    handleStateUpdate(JSON.parse(message.body));
                });
                client.subscribe('/topic/player/now-playing', () => {});
                client.subscribe('/topic/player/queue', (message) => {
                    queue.value = JSON.parse(message.body);
                });
                client.subscribe('/topic/users/online', (message) => {
                    userStore.setOnlineUsers(JSON.parse(message.body));
                });
                client.subscribe('/user/queue/player/state', (message) => {
                    handleStateUpdate(JSON.parse(message.body));
                });

                client.publish({ destination: '/app/player/resync' });
                Object.entries(userStore.bindings).forEach(([platform, id]) => {
                    if(id) bindAccount(platform, id);
                });
            },
            onDisconnect: () => {
                connected.value = false;
            }
        });
        client.activate();
        stompClient.value = client;
    };

    // ... (handleStateUpdate, Actions 等保持不变，省略以节省篇幅)
    const handleStateUpdate = (state) => {
        nowPlaying.value = state.nowPlaying;
        queue.value = state.queue;
        isPaused.value = state.isPaused;
        isShuffle.value = state.isShuffle;
        pauseTimeMillis.value = state.pauseTimeMillis || 0;
        if (state.serverTimestamp) {
            serverTimeOffset.value = state.serverTimestamp - Date.now();
        }
        if(state.onlineUsers) userStore.setOnlineUsers(state.onlineUsers);
    };

    const sendCommand = (dest, body = {}) => {
        if (!stompClient.value || !connected.value) return;
        stompClient.value.publish({ destination: dest, body: JSON.stringify(body) });
    };

    const playNext = () => sendCommand('/app/control/next');
    const togglePause = () => sendCommand('/app/control/toggle-pause');
    const toggleShuffle = () => sendCommand('/app/control/toggle-shuffle');
    const enqueue = (platform, musicId) => sendCommand('/app/enqueue', { platform, musicId });
    const enqueuePlaylist = (platform, playlistId) => sendCommand('/app/enqueue/playlist', { platform, playlistId });
    const topSong = (queueId) => sendCommand('/app/queue/top', { queueId });
    const removeSong = (queueId) => sendCommand('/app/queue/remove', { queueId });
    const bindAccount = (platform, accountId) => {
        sendCommand('/app/user/bind', { platform, accountId });
        userStore.updateBinding(platform, accountId);
    }
    const renameUser = (newName) => {
        sendCommand('/app/user/rename', { newName });
        userStore.saveName(newName);
    }
    watch(() => nowPlaying.value?.music?.id, async (newId) => {
        lyricText.value = '';
        if (!newId) return;
        const platform = nowPlaying.value.music.platform;
        try {
            const res = await axios.get(`/api/music/lyric/${platform}/${newId}`);
            lyricText.value = res.data || '';
        } catch (e) { console.error(e); }
    });

    return {
        nowPlaying,
        queue,
        isPaused,
        isShuffle,
        connected,
        getCurrentProgress,
        connect,
        playNext,
        togglePause,
        toggleShuffle,
        enqueue,
        enqueuePlaylist,
        topSong,
        removeSong,
        bindAccount,
        renameUser,
        lyricText
    };
});