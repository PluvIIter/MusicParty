// src/composables/useAudio.js

import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue';
import { socketService } from '../services/socket';
import { WS_DEST } from '../constants/api';
import { STORAGE_KEYS } from '../constants/storage';
import { createMediaSynchronizer } from '../services/playbackSync';

// 阈值常量
const ENDED_RESYNC_DELAY = 2500; // 当前曲播完但服务器未推新曲时，等待多久后主动重同步
const STALL_TIMEOUT = 15000; // Only recover after data stops arriving; slow downloads may keep buffering.
const POSITION_STATE_INTERVAL = 1000; // setPositionState 节流间隔（ms）
const SHELL_NOTIFY_INTERVAL = 15000; // 安卓原生壳通知栏进度刷新间隔（ms）

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
    let playAttempt = null;
    let resumeAttempts = 0;
    let resumeTimer = null;

    const synchronizer = createMediaSynchronizer();

    const alignToRoom = () => {
        const audio = audioRef.value;
        // 自动播放被拦截时音频不会走，纠偏/变速都无意义（#25 的守卫）
        if (!audio || !playerStore.nowPlaying || needsUserGesture.value) return;
        const duration = Number.isFinite(audio.duration)
            ? audio.duration
            : playerStore.nowPlaying.music.duration / 1000;
        if (!duration || duration <= 0) return;
        const target = Math.max(0, Math.min(playerStore.getCurrentProgress() / 1000, duration - 0.05));
        let targetBuffered = false,
            bufferAhead = 0;
        for (let i = 0; i < audio.buffered.length; i++) {
            const start = audio.buffered.start(i),
                end = audio.buffered.end(i);
            if (target >= start && target + 0.25 <= end) targetBuffered = true;
            if (audio.currentTime >= start && audio.currentTime <= end) bufferAhead = end - audio.currentTime;
        }
        const status = playerStore.getSyncStatus();
        const action = synchronizer.tick({
            now: performance.now(),
            mediaTime: audio.currentTime,
            targetTime: target,
            ready: audio.readyState >= 2,
            seeking: audio.seeking,
            ended: audio.ended,
            blocked: isErrorState.value,
            connected: playerStore.connected,
            fresh: status.fresh,
            paused: playerStore.isPaused,
            buffering: isBuffering.value,
            revision: status.revision,
            sampleId: status.sampleId,
            sampleAt: status.sampleAt,
            targetBuffered,
            bufferAhead,
        });
        // Keep pitch stable while making at most a two-percent timing correction.
        if ('preservesPitch' in audio) audio.preservesPitch = true;
        if (Math.abs(audio.playbackRate - action.rate) > 0.0005) audio.playbackRate = action.rate;
        if (action.seek !== undefined) audio.currentTime = action.seek;
    };

    // Screen wake locks only apply while visible; they cannot keep a background tab alive.
    const requestWakeLock = async () => {
        if ('wakeLock' in navigator && document.visibilityState === 'visible' && !wakeLock) {
            try {
                wakeLock = await navigator.wakeLock.request('screen');
                const acquired = wakeLock;
                acquired.addEventListener('release', () => {
                    if (wakeLock === acquired) wakeLock = null;
                });
                console.log('Wake Lock active');
            } catch (err) {
                console.warn('Wake Lock request failed:', err);
            }
        }
    };

    // 释放唤醒锁
    const releaseWakeLock = async () => {
        if (wakeLock !== null) {
            await wakeLock.release().catch(() => {});
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
            artwork: [{ src: music.coverUrl, sizes: '512x512', type: 'image/png' }],
        });

        // 2. 注册控制事件 (关键：告诉系统我们支持后台控制)
        try {
            navigator.mediaSession.setActionHandler('play', () => {
                if (playerStore.isPaused) playerStore.togglePause();
                else resumeLocalPlayback();
            });
            navigator.mediaSession.setActionHandler('pause', () => {
                if (!playerStore.isPaused) playerStore.togglePause();
            });
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
        bridge.updateMedia(
            JSON.stringify({
                title: music.name,
                artist: music.artists.join(' / '),
                position: Math.round(playerStore.localProgress / 1000),
                duration: Math.round((music.duration || 0) / 1000),
                paused: playerStore.isPaused,
                coverUrl: music.coverUrl || '',
            })
        );
    };

    // 尝试播放并处理浏览器拦截
    const safePlay = async () => {
        const audio = audioRef.value;
        if (!audio || !playerStore.nowPlaying || playerStore.isPaused || playAttempt) return;
        const attempt = { generation };
        playAttempt = attempt;
        try {
            await audio.play();
            if (attempt.generation !== generation || playAttempt !== attempt) return;
            isErrorState.value = false;
            needsUserGesture.value = false;
            if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'playing';
            updateMediaSession();
            requestWakeLock();
        } catch (e) {
            // A rejected promise belonging to the previous source must not block the new song.
            if (attempt.generation !== generation || playAttempt !== attempt) return;
            if (e.name === 'NotAllowedError') {
                needsUserGesture.value = true;
                isBuffering.value = false;
                clearTimeout(stallTimer);
                console.warn('Autoplay blocked. User interaction required.');
            } else if (e.name === 'AbortError') {
                scheduleResume();
            } else if (e.name !== 'AbortError') {
                console.warn('Play failed:', e);
                handleError();
            }
        } finally {
            if (playAttempt === attempt) playAttempt = null;
        }
    };

    const scheduleResume = () => {
        clearTimeout(resumeTimer);
        if (playerStore.isPaused || needsUserGesture.value || isErrorState.value) return;
        if (resumeAttempts >= 2) {
            if (document.visibilityState === 'visible') needsUserGesture.value = true;
            return;
        }
        const version = generation;
        resumeTimer = setTimeout(() => {
            if (
                version !== generation ||
                playerStore.isPaused ||
                !audioRef.value?.paused ||
                audioRef.value.ended ||
                needsUserGesture.value ||
                isErrorState.value
            )
                return;
            resumeAttempts++;
            safePlay();
        }, 750);
    };

    const resumeLocalPlayback = () => {
        const audio = audioRef.value;
        if (!audio || !playerStore.nowPlaying || playerStore.isPaused || isErrorState.value) return;
        if (!audio.paused && !audio.ended && audio.readyState >= 2 && !playAttempt) return;
        resumeAttempts = 0;
        clearTimeout(resumeTimer);
        // A pending background play() can remain unresolved after the browser resumes.
        playAttempt = null;
        needsUserGesture.value = false;
        if (audio.paused || audio.ended) synchronizer.resetMedia();
        if (audio.error) {
            handleError();
            return;
        }
        if (audio.readyState < 2) {
            onWaiting();
            if (audio.networkState === 0 || audio.networkState === 3) audio.load();
        } else {
            isBuffering.value = false;
            alignToRoom();
        }
        safePlay();
    };

    // === 1. 监听资源加载 (canplay) ===
    const checkAutoPlay = () => {
        if (!playerStore.nowPlaying || !audioRef.value || audioRef.value.readyState < 2) return;
        isBuffering.value = false;
        clearTimeout(endedTimer); // 新曲已到位，取消 ended 重同步等待
        clearTimeout(stallTimer);
        alignToRoom();

        alignToRoom();
        if (playerStore.isPaused) {
            audioRef.value.pause();
        } else if (!needsUserGesture.value && !isErrorState.value) {
            safePlay();
        }
    };

    // === 2. 监听后端状态变化 ===
    watch(
        () => playerStore.isPaused,
        (newPaused) => {
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
        }
    );

    // === 3. 监听切歌 ===
    watch(
        () => {
            const music = playerStore.nowPlaying?.music;
            return music ? `${playerStore.playbackId || ''}:${music.platform}:${music.id}:${music.url}` : '';
        },
        async () => {
            // #25：切歌时作废在途的地址刷新与重试
            generation++;
            const version = generation;
            playAttempt = null;
            resumeAttempts = 0;
            clearTimeout(resumeTimer);
            refreshAbort?.abort();
            refreshAbort = null;
            recovering = false;
            recoveredUrl.value = '';
            clearTimeout(retryTimer);
            // #24：复位同步器，新曲需要重新对齐
            synchronizer.resetMedia();
            clearTimeout(stallTimer);
            isBuffering.value = false;
            needsUserGesture.value = false;
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
                    artwork: [{ src: music.coverUrl, sizes: '512x512', type: 'image/png' }],
                });
            }

            retryCount.value = 0;
            isErrorState.value = false;
            updateMediaSession();
            notifyAndroidShell();
            // Mobile browsers may defer loading a new source in the background until play().
            // Do not depend exclusively on canplay, which may never arrive without this request.
            await nextTick();
            if (version === generation && playerStore.nowPlaying && !playerStore.isPaused) {
                if (audioRef.value?.readyState < 2) onWaiting();
                safePlay();
            }
        }
    );

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
            if (music.kind === 'SONG' && playerStore.playbackId) {
                const response = await fetch(
                    `/api/v2/playbacks/${encodeURIComponent(playerStore.playbackId)}/media/refresh`,
                    {
                        method: 'POST',
                        headers: { 'X-Room-Password': localStorage.getItem(STORAGE_KEYS.ROOM_PASSWORD) || '' },
                        cache: 'no-store',
                        signal: controller.signal,
                    }
                );
                if (!response.ok) throw new Error('播放地址刷新失败');
                const fresh = await response.json();
                if (version !== generation) return;
                if (fresh.id !== music.id || fresh.platform !== music.platform || !fresh.url)
                    throw new Error('曲目已切换');
                recoveredUrl.value = fresh.url;
            }
            if (version !== generation) return;
            isErrorState.value = false;
            playAttempt = null;
            // 换了新地址后需要重新对齐一次
            synchronizer.resetMedia();
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
            if (version === generation) {
                recovering = false;
                refreshAbort = null;
            }
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
        if (needsUserGesture.value) {
            if (!audioRef.value || !playerStore.nowPlaying || playerStore.isPaused) return;
            resumeAttempts = 0;
            playAttempt = null;
            synchronizer.resetMedia();
            if (audioRef.value.readyState >= 2) alignToRoom();
            // Invoke play inside the gesture; canplay may require playback to start loading.
            needsUserGesture.value = false;
            safePlay();
            return;
        }
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
                socketService.send(WS_DEST.COMPACT_RESYNC);
            }
        }, ENDED_RESYNC_DELAY);
    };

    // === 6. 持续缓冲看门狗：不再无限干等 ===
    const onWaiting = () => {
        isBuffering.value = true;
        clearTimeout(stallTimer);
        stallTimer = setTimeout(() => {
            if (audioRef.value && !playerStore.isPaused && !needsUserGesture.value && !isErrorState.value) {
                recoverAudio();
            }
        }, STALL_TIMEOUT);
    };
    const onProgress = () => {
        if (isBuffering.value && !recovering) onWaiting();
    };

    const onPlaying = () => {
        isBuffering.value = false;
        needsUserGesture.value = false;
        resumeAttempts = 0;
        clearTimeout(resumeTimer);
        clearTimeout(stallTimer);
    };

    const onPause = () => {
        if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'paused';
        if (!playerStore.isPaused && !audioRef.value?.ended) scheduleResume();
    };

    // 页面可见性变化监听
    const handleVisibilityChange = () => {
        if (document.visibilityState === 'visible') {
            playerStore.tryReconnect();
            // Ask for fresh state; foregrounding must not force a media seek.
            socketService.send(WS_DEST.COMPACT_RESYNC);
            resumeLocalPlayback();
            if (!playerStore.isPaused) requestWakeLock();
        }
    };
    const handlePageShow = () => handleVisibilityChange();

    // 网络状态监听
    const handleNetworkChange = () => {
        if (navigator.onLine) {
            console.log('[Network] Back online, checking socket...');
            playerStore.tryReconnect();
            socketService.send(WS_DEST.COMPACT_RESYNC);
            resumeLocalPlayback();
        }
    };

    // === 7. 进度条同步 ===
    onMounted(() => {
        document.addEventListener('visibilitychange', handleVisibilityChange);
        window.addEventListener('online', handleNetworkChange);
        window.addEventListener('pageshow', handlePageShow);
        document.addEventListener('resume', handlePageShow);

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
            if (
                now - lastPositionStateAt >= POSITION_STATE_INTERVAL &&
                'mediaSession' in navigator &&
                navigator.mediaSession.setPositionState
            ) {
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
        generation++;
        playAttempt = null;
        clearTimeout(resumeTimer);
        if (typeof window !== 'undefined') {
            delete window.MusicPartyControl;
        }
        document.removeEventListener('visibilitychange', handleVisibilityChange);
        window.removeEventListener('online', handleNetworkChange);
        window.removeEventListener('pageshow', handlePageShow);
        document.removeEventListener('resume', handlePageShow);
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
        onPause,
        onProgress,
        retryLocally,
    };
}
