// src/composables/useAudio.js

import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue';
import { socketService } from '../services/socket';
import { WS_DEST } from '../constants/api';
import { STORAGE_KEYS } from '../constants/keys';

// 阈值常量
const ENDED_RESYNC_DELAY = 2500;       // 当前曲播完但服务器未推新曲时，等待多久后主动重同步
const STALL_TIMEOUT = 15000;           // Only recover after data stops arriving; slow downloads may keep buffering.
const POSITION_STATE_INTERVAL = 1000;  // setPositionState 节流间隔（ms）
const SHELL_NOTIFY_INTERVAL = 15000;   // 安卓原生壳通知栏进度刷新间隔（ms）

export function useAudio(audioRef, playerStore) {
    const localProgress = ref(0);
    const isBuffering = ref(false);
    const retryCount = ref(0);
    const isErrorState = ref(false);
    const needsUserGesture = ref(false);
    const recoveredUrl = ref('');
    let syncTimer = null;
    let wakeLock = null;
    let endedTimer = null;
    let endedTrackId = null;
    let stallTimer = null;
    let lastPositionStateAt = 0;
    let lastShellNotifyAt = 0;
    let generation = 0;
    let recovering = false;
    let retryTimer = null;
    let refreshAbort = null;
    let initialAlignment = true;
    let lastAlignment = -Infinity;
    const alignToRoom = () => {
        const audio = audioRef.value;
        if (!audio || !playerStore.nowPlaying || audio.readyState < 2 || audio.seeking
            || isBuffering.value || isErrorState.value || needsUserGesture.value) return;
        const duration = Number.isFinite(audio.duration) ? audio.duration : playerStore.nowPlaying.music.duration / 1000;
        if (!duration || duration <= 0) return;
        const target = Math.max(0, Math.min(playerStore.getCurrentProgress() / 1000, duration - 0.05));
        const difference = Math.abs(audio.currentTime - target);
        if ((initialAlignment && difference > 0.35)
            || (!initialAlignment && difference > 2.5 && performance.now() - lastAlignment > 3000)) {
            initialAlignment = false;
            lastAlignment = performance.now();
            audio.currentTime = target;
        } else initialAlignment = false;
    };

    // 请求唤醒锁 (防止 WebSocket 断连)
    const requestWakeLock = async () => {
        if ('wakeLock' in navigator) {
            try {
                wakeLock = await navigator.wakeLock.request('screen');
                console.log('Wake Lock active');
            } catch (err) {
                console.warn('Wake Lock request failed:', err);
            }
        }
    };

    // 释放唤醒锁
    const releaseWakeLock = async () => {
        if (wakeLock !== null) {
            await wakeLock.release();
            wakeLock = null;
        }
    };

    // 更新系统媒体中心 (锁屏控制)
    const updateMediaSession = () => {
        if (!('mediaSession' in navigator) || !playerStore.nowPlaying) return;

        const music = playerStore.nowPlaying.music;

        // 1. 设置元数据
        navigator.mediaSession.metadata = new MediaMetadata({
            title: music.name,
            artist: music.artists.join(' / '),
            artwork: [
                { src: music.coverUrl, sizes: '512x512', type: 'image/png' }
            ]
        });

        // 2. 注册控制事件 (关键：告诉系统我们支持后台控制)
        try {
            navigator.mediaSession.setActionHandler('play', () => playerStore.togglePause());
            navigator.mediaSession.setActionHandler('pause', () => playerStore.togglePause());
            navigator.mediaSession.setActionHandler('previoustrack', null); // 暂不支持上一首
            navigator.mediaSession.setActionHandler('nexttrack', () => playerStore.playNext());
        } catch (e) {
            console.warn('Media Session actions warning:', e);
        }
    };

    // 安卓原生壳：把播放状态推给通知栏媒体控制器（window.AndroidBridge 由原生注入）
    const notifyAndroidShell = () => {
        const bridge = typeof window !== 'undefined' ? window.AndroidBridge : null;
        if (!bridge?.updateMedia || !playerStore.nowPlaying) return;
        const music = playerStore.nowPlaying.music;
        bridge.updateMedia(JSON.stringify({
            title: music.name,
            artist: music.artists.join(' / '),
            position: Math.round(playerStore.localProgress / 1000),
            duration: Math.round((music.duration || 0) / 1000),
            paused: playerStore.isPaused,
            coverUrl: music.coverUrl || '',
        }));
    };

    // 尝试播放并处理浏览器拦截
    const safePlay = async () => {
        if (!audioRef.value || !playerStore.nowPlaying) return;

        try {
            await audioRef.value.play();
            isErrorState.value = false;
            needsUserGesture.value = false;
            updateMediaSession();
            requestWakeLock();
        } catch (e) {
            if (e.name === 'NotAllowedError') {
                needsUserGesture.value = true;
                console.warn("Autoplay blocked. User interaction required.");
            } else if (e.name !== 'AbortError') {
                console.warn("Play failed:", e);
            }
        }
    };

    // === 1. 监听资源加载 (canplay) ===
    const checkAutoPlay = () => {
        if (!playerStore.nowPlaying) return;
        if (!audioRef.value || audioRef.value.readyState < 2) return;
        isBuffering.value = false;
        clearTimeout(endedTimer);   // 新曲已到位，取消 ended 重同步等待
        clearTimeout(stallTimer);
        alignToRoom();

        if (playerStore.isPaused) {
            audioRef.value.pause();
        } else {
            safePlay();
        }
    };

    // === 2. 监听后端状态变化 ===
    watch(() => playerStore.isPaused, (newPaused) => {
        if (!audioRef.value) return;
        if (newPaused) {
            audioRef.value.pause();
            alignToRoom();
            if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'paused';
            releaseWakeLock();
        } else {
            alignToRoom();
            safePlay();
            if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'playing';
        }
        notifyAndroidShell();
    });

    // === 3. 监听切歌 ===
    watch(() => {
        const music = playerStore.nowPlaying?.music;
        return music ? `${music.platform}:${music.id}:${music.url}` : '';
    }, () => {
        generation++;
        refreshAbort?.abort();
        refreshAbort = null;
        recovering = false;
        recoveredUrl.value = '';
        clearTimeout(retryTimer);
        clearTimeout(stallTimer);
        isBuffering.value = false;
        needsUserGesture.value = false;
        initialAlignment = true;
        localProgress.value = 0;
        // 服务器已推新曲（或清空），取消 ended 重同步等待
        clearTimeout(endedTimer);
        endedTrackId = null;

        // 更新媒体中心信息 (锁屏显示)
        if ('mediaSession' in navigator && playerStore.nowPlaying) {
            const music = playerStore.nowPlaying.music;
            navigator.mediaSession.metadata = new MediaMetadata({
                title: music.name,
                artist: music.artists.join(' / '),
                artwork: [{ src: music.coverUrl, sizes: '512x512', type: 'image/png' }]
            });
        }

        retryCount.value = 0;
        isErrorState.value = false;
        updateMediaSession();
        notifyAndroidShell();
    });

    // === 4. 错误重试机制 ===
    const recoverAudio = async () => {
        if (!playerStore.nowPlaying?.music?.url) return;
        if (recovering) return;
        if (retryCount.value >= 2) {
            isBuffering.value = false;
            isErrorState.value = true;
            return;
        }
        const version = generation;
        const music = playerStore.nowPlaying.music;
        recovering = true;
        isBuffering.value = true;
        clearTimeout(stallTimer);
        retryCount.value++;
        refreshAbort = new AbortController();
        const controller = refreshAbort;
        const timeout = setTimeout(() => controller.abort(), 14000);
        try {
            if (music.platform === 'netease') {
                const response = await fetch(`/api/music/refresh/netease/${encodeURIComponent(music.id)}`, {
                    headers: { 'X-Room-Password': localStorage.getItem(STORAGE_KEYS.ROOM_PASSWORD) || '' },
                    cache: 'no-store', signal: controller.signal
                });
                if (!response.ok) throw new Error('播放地址刷新失败');
                const fresh = await response.json();
                if (version !== generation) return;
                if (fresh.id !== music.id || fresh.platform !== music.platform || !fresh.url) throw new Error('曲目已切换');
                recoveredUrl.value = fresh.url;
            }
            if (version !== generation) return;
            isErrorState.value = false;
            initialAlignment = true;
            await nextTick();
            // Also reload when the API legitimately returns the same URL.
            audioRef.value?.load();
        } catch {
            if (version !== generation) return;
            isBuffering.value = false;
            if (retryCount.value >= 2) isErrorState.value = true;
            else retryTimer = setTimeout(recoverAudio, 1500);
        } finally {
            clearTimeout(timeout);
            if (version === generation) { recovering = false; refreshAbort = null; }
        }
    };
    const handleError = () => {
        if (!playerStore.nowPlaying?.music?.url) return;
        clearTimeout(stallTimer);
        clearTimeout(retryTimer);
        if (retryCount.value >= 2) {
            isBuffering.value = false;
            isErrorState.value = true;
            return;
        }
        // Let the shared short-lived link cache expire before asking again.
        retryTimer = setTimeout(recoverAudio, retryCount.value ? 5500 : 1000);
    };
    const retryLocally = () => {
        if (needsUserGesture.value) { needsUserGesture.value = false; initialAlignment = true; checkAutoPlay(); return; }
        retryCount.value = 0;
        isErrorState.value = false;
        recoverAudio();
    };

    // === 5. 音频自然结束兜底：服务器没推新曲就主动重同步 ===
    const handleEnded = () => {
        if (playerStore.isPaused) return; // 服务器暂停态：保持暂停，不动作

        endedTrackId = playerStore.nowPlaying?.music?.id ?? null;
        clearTimeout(endedTimer);
        endedTimer = setTimeout(() => {
            const currentId = playerStore.nowPlaying?.music?.id ?? null;
            // 超时仍停留在同一曲（或无曲目）→ 服务器广播丢失/连接异常
            if (currentId === endedTrackId) {
                console.warn('[Ended] No next track pushed, forcing resync...');
                playerStore.tryReconnect();
                socketService.send(WS_DEST.RESYNC);
            }
        }, ENDED_RESYNC_DELAY);
    };

    // === 6. 持续缓冲看门狗：不再无限干等 ===
    const onWaiting = () => {
        isBuffering.value = true;
        clearTimeout(stallTimer);
        stallTimer = setTimeout(() => {
            if (audioRef.value && !playerStore.isPaused) {
                recoverAudio();
            }
        }, STALL_TIMEOUT);
    };
    const onProgress = () => {
        if (isBuffering.value && !recovering) onWaiting();
    };

    const onPlaying = () => {
        isBuffering.value = false;
        clearTimeout(stallTimer);
    };

    // 页面可见性变化监听
    const handleVisibilityChange = async () => {
        if (document.visibilityState === 'visible') {
            if (!playerStore.isPaused) {
                await requestWakeLock();
            }
            playerStore.tryReconnect();
            // Ask for fresh state; foregrounding must not force a media seek.
            socketService.send(WS_DEST.RESYNC);
        }
    };

    // 网络状态监听
    const handleNetworkChange = () => {
        if (navigator.onLine) {
            console.log('[Network] Back online, checking socket...');
            playerStore.tryReconnect();
            socketService.send(WS_DEST.RESYNC);
        }
    };

    // === 7. 进度条同步 ===
    onMounted(() => {
        document.addEventListener('visibilitychange', handleVisibilityChange);
        window.addEventListener('online', handleNetworkChange);

        // 注册通知栏媒体控制的 Web 端动作（由原生 evaluateJavascript 调用）
        if (typeof window !== 'undefined') {
            window.MusicPartyControl = {
                togglePause: () => playerStore.togglePause(),
                next: () => playerStore.playNext(),
            };
        }

        syncTimer = setInterval(() => {
            if (!playerStore.nowPlaying) {
                localProgress.value = 0;
                return;
            }

            // 2. 更新 UI 绑定值 (localProgress)
            // UI follows decoded media, including failures/autoplay blocks; never alternate with a phantom clock.
            alignToRoom();
            if (audioRef.value?.readyState >= 2 && !audioRef.value.seeking) {
                localProgress.value = audioRef.value.currentTime * 1000;
            }

            // 4. 锁屏/通知栏真实进度（节流，避免每 200ms 都调用）
            const now = Date.now();
            if (now - lastPositionStateAt >= POSITION_STATE_INTERVAL
                && 'mediaSession' in navigator && navigator.mediaSession.setPositionState) {
                lastPositionStateAt = now;
                try {
                    navigator.mediaSession.setPositionState({
                        duration: playerStore.nowPlaying.music.duration / 1000,
                        playbackRate: audioRef.value?.playbackRate || 1,
                        position: playerStore.localProgress / 1000,
                    });
                } catch (e) {
                    // 某些状态（如无有效时长）会抛异常，忽略
                }
            }

            // 5. 安卓原生壳：定期刷新通知栏进度（WebView 无原生 MediaSession，靠轮询补偿）
            if (now - lastShellNotifyAt >= SHELL_NOTIFY_INTERVAL) {
                lastShellNotifyAt = now;
                notifyAndroidShell();
            }
        }, 200);
    });

    onUnmounted(() => {
        if (typeof window !== 'undefined') {
            delete window.MusicPartyControl;
        }
        document.removeEventListener('visibilitychange', handleVisibilityChange);
        window.removeEventListener('online', handleNetworkChange);
        clearInterval(syncTimer);
        clearTimeout(endedTimer);
        clearTimeout(stallTimer);
        clearTimeout(retryTimer);
        refreshAbort?.abort();
        releaseWakeLock();
    });

    return {
        localProgress,
        isBuffering,
        isErrorState,
        needsUserGesture,
        recoveredUrl,
        retryCount,
        handleError,
        checkAutoPlay,
        handleEnded,
        onWaiting,
        onPlaying,
        onProgress,
        retryLocally
    };
}
