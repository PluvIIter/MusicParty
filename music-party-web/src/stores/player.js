// File Path: music-party-web\src\stores\player.js

import { defineStore } from 'pinia';
import { ref, watch } from 'vue';
import { Client } from '@stomp/stompjs';
import { useUserStore } from './user';
import axios from 'axios';

export const usePlayerStore = defineStore('player', () => {
    const userStore = useUserStore();

    // ... (其他状态变量保持不变)
    const nowPlaying = ref(null);
    const queue = ref([]);
    const isPaused = ref(false);
    const pauseTimeMillis = ref(0);
    const isShuffle = ref(false);
    const serverTimeOffset = ref(0);
    const lyricText = ref('');

    const stompClient = ref(null);
    const connected = ref(false);

    // ... (getCurrentProgress 保持不变)
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

    const connect = () => {
        const savedName = localStorage.getItem('mp_username') || 'Guest';

        const client = new Client({
            brokerURL: `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/ws`,
            connectHeaders: {
                'user-name': savedName
            },
            onConnect: (frame) => {
                connected.value = true;

                // 🟢 修改核心逻辑：处理 /app/user/me 的回调
                client.subscribe('/app/user/me', (message) => {
                    const me = JSON.parse(message.body);
                    console.log("Identified as:", me);

                    // 1. 初始化用户，并获取是否需要同步的标志
                    const needsSync = userStore.initUser(me.sessionId, me.name);

                    // 2. 如果前端发现名字不一致，立即发起重命名
                    if (needsSync) {
                        console.log(`Name mismatch detected (Local: ${userStore.currentUser.name} vs Server: ${me.name}). Auto-correcting...`);
                        renameUser(userStore.currentUser.name);
                    }
                });

                // ... (其余订阅逻辑保持不变)
                client.subscribe('/topic/player/state', (message) => {
                    handleStateUpdate(JSON.parse(message.body));
                });

                client.subscribe('/topic/player/now-playing', (message) => { });

                client.subscribe('/topic/player/queue', (message) => {
                    queue.value = JSON.parse(message.body);
                });

                client.subscribe('/topic/users/online', (message) => {
                    userStore.setOnlineUsers(JSON.parse(message.body));
                });

                client.subscribe('/user/queue/player/state', (message) => {
                    handleStateUpdate(JSON.parse(message.body));
                });

                // 这里原本的盲发重命名逻辑可以保留作为兜底，也可以移除，
                // 因为上面的 needsSync 逻辑更加精准。建议保留以防万一。
                const savedName = localStorage.getItem('mp_username');
                if (savedName) {
                    renameUser(savedName);
                }

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

    // ... (handleStateUpdate 和 Actions 保持不变)
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
        } catch (e) {
            console.error("Lyric fetch failed", e);
        }
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