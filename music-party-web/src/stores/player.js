import { defineStore } from 'pinia';
import { ref, watch } from 'vue';
import { Client } from '@stomp/stompjs';
import { useUserStore } from './user';
import axios from 'axios';

export const usePlayerStore = defineStore('player', () => {
    const userStore = useUserStore();
    
    // 播放器状态
    const nowPlaying = ref(null);
    const queue = ref([]);
    const isPaused = ref(false);
	const pauseTimeMillis = ref(0);
    const isShuffle = ref(false);
    const serverTimeOffset = ref(0); // 本地与服务器时间差
    const lyricText = ref('');

    // WebSocket 客户端
    const stompClient = ref(null);
    const connected = ref(false);

    // 计算当前理论播放进度 (毫秒)
    const getCurrentProgress = () => {
        if (!nowPlaying.value) return 0;

        // 后端发来的 startTimeMillis 已经是 (OriginalStart + TotalPaused)
        // 所以我们不需要再手动减去已过去的暂停时间
        const effectiveStartTime = nowPlaying.value.startTimeMillis;

        if (isPaused.value) {
            // 暂停状态：
            // 进度 = 暂停发生的时刻 - 有效开始时间
            // 解释：比如 10:00 开始，10:05 暂停。pauseTime=10:05。
            // 进度 = 10:05 - 10:00 = 5分钟。这是固定的。
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
                // 获取 SessionID (SockJS 实际上会在 URL 里，但 STOMP 握手后 frame.headers['user-name'] 通常是 Principal)
                // 这里我们假设后端通过 UserDestination 能够处理

                // 🟢 修改 3: 订阅 /app/user/me 以获取自己的 SessionID 并初始化 UserStore
                client.subscribe('/app/user/me', (message) => {
                    const me = JSON.parse(message.body);
                    console.log("Identified as:", me);
                    // 这一步至关重要，让 UserStore 知道哪个 SessionID 是自己
                    userStore.initUser(me.sessionId, me.name);
                });

                // 1. 订阅公共频道
                client.subscribe('/topic/player/state', (message) => {
                    handleStateUpdate(JSON.parse(message.body));
                });
                
                client.subscribe('/topic/player/now-playing', (message) => {
                    // 仅切歌信号，通常 state 也会随之更新
                });
                
                client.subscribe('/topic/player/queue', (message) => {
                    queue.value = JSON.parse(message.body);
                });

                client.subscribe('/topic/users/online', (message) => {
                    userStore.setOnlineUsers(JSON.parse(message.body));
                });

                // 2. 订阅个人频道 (用于 Resync 和 获取 SessionId)
                // Spring Security 的 STOMP 支持会将 /user/queue/... 路由给特定用户
                client.subscribe('/user/queue/player/state', (message) => {
                     handleStateUpdate(JSON.parse(message.body));
                });
				
				const savedName = localStorage.getItem('mp_username');
                if (savedName) {
                    renameUser(savedName);
                }

                // 3. 立即请求同步状态
                client.publish({ destination: '/app/player/resync' });
                
                // 4. 发送绑定信息 (如果有)
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
		if (state.serverTimestamp) {
            serverTimeOffset.value = state.serverTimestamp - Date.now();
            console.log("Time synced. Offset:", serverTimeOffset.value, "ms");
        }
        if(state.onlineUsers) userStore.setOnlineUsers(state.onlineUsers);
    };

    // --- Actions ---

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
        //修改点：调用 userStore 的 saveName 来持久化
        userStore.saveName(newName); 
    }

    watch(() => nowPlaying.value?.music?.id, async (newId) => {
        // 重置歌词
        lyricText.value = '';

        if (!newId) return;

        const platform = nowPlaying.value.music.platform;
        try {
            // 调用后端接口获取歌词
            // 注意：确保后端 Controller 路径是 /api/music/lyric/{platform}/{id}
            // 如果你之前的后端写的是其他路径，请在这里调整
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