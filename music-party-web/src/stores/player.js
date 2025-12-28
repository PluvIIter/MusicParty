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
    const lastControlTime = ref(0);
    const LOCAL_COOLDOWN = 800; // 本地防抖 800ms (略小于后端，提升手感)
    const isLoading = ref(false);

    // 🟢 辅助：权限检查
    const requireAuth = () => {
        if (userStore.isGuest) {
            userStore.showNameModal = true; // 唤起弹窗
            return false;
        }
        return true;
    };

    // 🟢 辅助：构建文案
    const formatEventMessage = (action, userId, payload) => {
        const userName = userStore.resolveName(userId);
        switch (action) {
            case 'SKIP': return `${userName} 切到了下一首`;
            case 'PAUSE': return `${userName} 暂停了播放`;
            case 'RESUME': return `${userName} 继续了播放`;
            case 'ADD': return `${userName} 添加了: ${payload}`;
            case 'IMPORT': return `${userName} 导入了歌单 (${payload}首)`;
            case 'TOP': return `${userName} 置顶了: ${payload}`;
            case 'REMOVE': return `${userName} 移除了: ${payload}`;
            case 'SHUFFLE': return `${userName} ${payload === 'ON' ? '开启' : '关闭'}了随机播放`;
            case 'RESET': return `系统已被重置`;
            default: return `${userName} 执行了操作`;
        }
    };


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

    // 🟢 辅助：构建标题
    const deriveTitle = (action) => {
        const map = {
            'SKIP': 'TRACK SWITCHED',
            'ADD': 'ADDED TO QUEUE',
            'IMPORT': 'PLAYLIST IMPORT',
            'PAUSE': 'PLAYER PAUSED',
            'RESUME': 'PLAYER RESUMED',
            'SHUFFLE': 'SHUFFLE MODE',
            'TOP': 'PRIORITY UPDATE',
            'REMOVE': 'QUEUE REMOVAL',
            'RESET': 'SYSTEM ALERT'
        };
        return map[action] || 'SYSTEM NOTICE';
    };

    const connect = () => {
        const savedName = localStorage.getItem('mp_username') || 'Guest';
        const token = userStore.userToken;

        const client = new Client({
            brokerURL: `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/ws`,
            connectHeaders: {
                'user-name': savedName,
                'user-token': token
            },
            heartbeatIncoming: 10000,
            heartbeatOutgoing: 10000,
            onConnect: (frame) => {
                connected.value = true;

                client.subscribe('/app/user/me', (message) => {
                    const me = JSON.parse(message.body);
                    const needsSync = userStore.initUser(me.sessionId, me.name);
                    if (needsSync) {
                        renameUser(userStore.currentUser.name);
                    }
                });

                client.subscribe('/topic/player/events', (message) => {
                    const event = JSON.parse(message.body);

                    if (event.type === 'ERROR' && event.message.includes('taken')) {
                        show({
                            title: 'NAME TAKEN',
                            message: '该代号已被占用，请更换。',
                            type: 'error'
                        });
                        // 可以在这里把 showNameModal 重新打开
                        userStore.showNameModal = true;
                        return;
                    }
                    // event: { type, action, userId, payload }
                    const msgText = formatEventMessage(event.action, event.userId, event.payload);

                    show({
                        title: deriveTitle(event.action),
                        message: msgText,
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

    const handleStateUpdate = (state) => {
        nowPlaying.value = state.nowPlaying;
        queue.value = state.queue;
        isPaused.value = state.isPaused;
        isShuffle.value = state.isShuffle;
        pauseTimeMillis.value = state.pauseTimeMillis || 0;
        isLoading.value = state.isLoading || false;
        if (state.serverTimestamp) {
            serverTimeOffset.value = state.serverTimestamp - Date.now();
        }
        if(state.onlineUsers) userStore.setOnlineUsers(state.onlineUsers);
    };

    const sendCommand = (dest, body = {}) => {
        if (!stompClient.value || !connected.value) return;
        stompClient.value.publish({ destination: dest, body: JSON.stringify(body) });
    };

    const checkCooldown = () => {
        const now = Date.now();
        if (now - lastControlTime.value < LOCAL_COOLDOWN) {
            show({
                title: "RATE LIMITED",
                message: "操作频繁，请等待...",
                type: "error",
                duration: 2000
            });
            return false;
        }
        lastControlTime.value = now;
        return true;
    };
    const playNext = () => {
        if(requireAuth() && checkCooldown()) sendCommand('/app/control/next');
    }
    const togglePause = () => {
        if(requireAuth() && checkCooldown()) sendCommand('/app/control/toggle-pause');
    }
    const toggleShuffle = () => {
        if(requireAuth() && checkCooldown()) sendCommand('/app/control/toggle-shuffle');
    }
    const enqueue = (platform, musicId) => {
        if(requireAuth()) sendCommand('/app/enqueue', { platform, musicId });
    }
    const enqueuePlaylist = (platform, playlistId) => {
        if(requireAuth()) sendCommand('/app/enqueue/playlist', { platform, playlistId });
    }
    const topSong = (queueId) => {
        if(requireAuth()) sendCommand('/app/queue/top', { queueId });
    }
    const removeSong = (queueId) => {
        if(requireAuth()) sendCommand('/app/queue/remove', { queueId });
    }
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
        lyricText,
        requireAuth,
        isLoading
    };
});