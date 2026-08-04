# 移动端后台/锁屏播放稳定性 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除 Android 移动端后台/锁屏时切歌中断、不再播下一首、失去同步的问题，并引导 PWA 安装以支持 iOS 后台播放。

**Architecture:** 后端 `MusicPlayerService` 新增周期状态广播（默认 5s，可配置），客户端每次收到广播重锚同步进度，从根上防漂移；前端「切歌间隙不清空 src」让主音频在服务器拉取下一首时持续出声；`@ended`/缓冲看门狗在连接异常时主动 RESYNC 兜底；移除保活音轨换取干净听感；新增 PWA manifest 引导。

**Tech Stack:** Spring Boot (Java, `@Scheduled`, `AppProperties`)、Vue 3 (Composition API)、Pinia、`@stomp/stompjs`、Vite。

## Global Constraints

- 目标平台：Android Chrome 为主；iOS 尽力而为（通过 PWA 引导，不承诺纯浏览器 100% 后台）。
- 保活音轨整体移除（`ALIVE_WAV`、`keepAliveEnabled`、`//alive` 命令）。
- 不做服务器提前预载下一首；不改 Wake Lock 现状。
- 周期广播默认间隔 5000ms，配置项 `app.music-api.player.sync-broadcast-interval-ms`。
- 看门狗阈值：切歌 RESYNC 等待 2.5s；缓冲超时 8s；`setPositionState` 节流 1s。
- 后端配置前缀为 `app.music-api`（`AppProperties` 的 `@ConfigurationProperties`），测试用 `./mvnw test`（Git Bash）或 `.\mvnw.cmd test`（PowerShell）。
- 前端构建检查：`cd music-party-web && npm run build`。
- 所有提交信息结尾带 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。

---

### Task 1: 后端周期状态广播 + 配置项（TDD）

**Files:**
- Modify: `src/main/java/org/thornex/musicparty/config/AppProperties.java:46-52`（`PlayerConfig` 加字段）
- Modify: `src/main/resources/application.yml:36-37`（`app.music-api.player` 加配置）
- Modify: `src/main/java/org/thornex/musicparty/service/MusicPlayerService.java`（加 `@Scheduled` 方法 + 测试辅助方法）
- Modify: `src/test/java/org/thornex/musicparty/service/MusicPlayerServiceStateTest.java`

**Interfaces:**
- Consumes: 无（独立于其他任务）
- Produces: `MusicPlayerService.broadcastSyncHeartbeat()`（Task 3 前端依赖其周期广播来重锚同步）；`AppProperties.PlayerConfig.syncBroadcastIntervalMs`（long，默认 5000）

- [ ] **Step 1: 先写失败测试**

在 `MusicPlayerServiceStateTest.java` 顶部 import 区域已有 `PlayerState`、`AppProperties`、`ApplicationEventPublisher`、`NeteaseMusicApiService`、`List`、Mockito static。新增需 `PlayableMusic`、`PrivateDjSegment`（同包 `org.thornex.musicparty.dto`，需 import）。将现有 `build(...)` 改为带 publisher 参数的双重载，并新增两个测试用例与测试辅助方法：

```java
package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.PlayerState;
import org.thornex.musicparty.dto.PrivateDjSegment;
import org.thornex.musicparty.event.PlayerStateEvent;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicPlayerServiceStateTest {

    private MusicPlayerService build(AppProperties props, NeteaseMusicApiService api) {
        return build(props, api, mock(ApplicationEventPublisher.class));
    }

    private MusicPlayerService build(AppProperties props, NeteaseMusicApiService api, ApplicationEventPublisher publisher) {
        return new MusicPlayerService(
                List.of(),
                mock(org.thornex.musicparty.service.UserService.class),
                mock(org.thornex.musicparty.service.LocalCacheService.class),
                mock(org.thornex.musicparty.service.stream.LiveStreamService.class),
                mock(MusicQueueManager.class),
                publisher,
                props,
                api,
                mock(PrivateDjService.class)
        );
    }

    @Test
    void configSummaryExposesPrivateDjAndCookieState() {
        AppProperties props = new AppProperties();
        props.getPrivateDj().setMasterEnabled(true);
        props.getPrivateDj().setMode("DJ");
        props.getPrivateDj().setCustodyEnabled(true);
        NeteaseMusicApiService api = mock(NeteaseMusicApiService.class);
        when(api.isCookieConfigured()).thenReturn(true);

        PlayerState state = build(props, api).getCurrentPlayerState();

        assertTrue(state.config().neteaseCookieConfigured());
        assertTrue(state.config().privateDj().masterEnabled());
        assertEquals("DJ", state.config().privateDj().mode());
        assertTrue(state.config().privateDj().custodyEnabled());
    }

    @Test
    void syncHeartbeatBroadcastsWhileMusicPlaying() {
        AppProperties props = new AppProperties();
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        MusicPlayerService service = build(props, mock(NeteaseMusicApiService.class), publisher);

        // 模拟一首正在播放的歌曲
        service.applyFmDjSegmentForTest(
                new PlayableMusic("1", "Song", List.of("Artist"), 180_000L, "netease", "http://x/1.mp3", "http://x/1.jpg", false),
                new PrivateDjSegment.Song("1", "Song", List.of("Artist"), 180_000L, "http://x/1.jpg"));

        clearInvocations(publisher);

        service.broadcastSyncHeartbeat();

        verify(publisher, times(1)).publishEvent(any(PlayerStateEvent.class));
    }

    @Test
    void syncHeartbeatSkipsWhenIdleAndPaused() {
        AppProperties props = new AppProperties();
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        MusicPlayerService service = build(props, mock(NeteaseMusicApiService.class), publisher);

        service.setPausedForTest(true);
        clearInvocations(publisher);

        service.broadcastSyncHeartbeat();

        verify(publisher, never()).publishEvent(any());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw test -Dtest=MusicPlayerServiceStateTest`（Git Bash）
Expected: FAIL —— `broadcastSyncHeartbeat`、`setPausedForTest` 未定义，编译错误。

- [ ] **Step 3: 实现配置项**

`AppProperties.java` 的 `PlayerConfig`（当前在第 46-52 行）末尾加字段：

```java
    @Data
    public static class PlayerConfig {
        private int maxPlaylistImportSize = 100;
        private boolean voteSkipEnabled = false;
        private double voteSkipThreshold = 0.5;
        private int voteSkipWaitTime = 15;
        private long syncBroadcastIntervalMs = 5000; // 周期状态广播间隔（ms），修复移动端后台同步漂移
    }
```

`application.yml` 的 `app.music-api.player` 段（当前第 36-37 行）加一行：

```yaml
    player:
      max-playlist-import-size: ${PLAYLIST_IMPORT_LIMIT:100}
      sync-broadcast-interval-ms: ${SYNC_BROADCAST_INTERVAL_MS:5000}
```

- [ ] **Step 4: 实现周期广播方法与测试辅助方法**

在 `MusicPlayerService.java` 的 `cleanupIdlePlayer()` 方法（约第 1076 行）之后、`// --- Broadcasting and Helper Methods ---` 之前，新增：

```java
    /**
     * 周期状态广播（心跳）：让所有客户端周期性重锚播放进度，主动防漂移，
     * 同时让客户端能通过"长时间收不到广播"识别假连接。空闲（无曲且暂停）时跳过。
     */
    @Scheduled(fixedRateString = "${app.music-api.player.sync-broadcast-interval-ms:5000}")
    public void broadcastSyncHeartbeat() {
        if (currentMusic.get() == null && isPaused.get()) {
            return;
        }
        broadcastFullPlayerState();
    }
```

在 `// ---- 测试辅助（仅测试使用）----` 区块（约第 330-334 行）追加一个辅助方法：

```java
    void setPausedForTest(boolean paused) { isPaused.set(paused); }
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./mvnw test -Dtest=MusicPlayerServiceStateTest`（Git Bash）
Expected: PASS（3 个用例全绿）。再跑一次全量：`./mvnw test`，确认无回归（可能较慢，属正常）。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/org/thornex/musicparty/config/AppProperties.java src/main/resources/application.yml src/main/java/org/thornex/musicparty/service/MusicPlayerService.java src/test/java/org/thornex/musicparty/service/MusicPlayerServiceStateTest.java
git commit -m "feat: 播放器周期状态广播，修复移动端后台同步漂移

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: 前端清理保活开关（非 AudioEngine 部分）

> 说明：`AudioEngine.vue` 里的保活元素与逻辑放在 Task 3 全量重写时一并移除，避免本任务与 Task 3 对同一文件冲突。

**Files:**
- Modify: `music-party-web/src/stores/ui.js:11,24-27`（删 `keepAliveEnabled`、`toggleKeepAlive`）
- Modify: `music-party-web/src/constants/keys.js:8`（删 `KEEP_ALIVE`）
- Modify: `music-party-web/src/components/ChatOverlay.vue:205,214,411-417`（删 `//alive` 分支与 `uiStore` 引用）
- Modify: `README.md:29,143`

**Interfaces:**
- Consumes: 无
- Produces: 无（Task 3 不再依赖这些符号）

- [ ] **Step 1: 删 `ui.js` 中的保活状态**

`music-party-web/src/stores/ui.js`：
- 删除第 11 行 `const keepAliveEnabled = ref(localStorage.getItem(STORAGE_KEYS.KEEP_ALIVE) === 'true');`
- 删除第 24-27 行的 `toggleKeepAlive` 方法
- 从 return 对象中删除 `keepAliveEnabled,` 与 `toggleKeepAlive,` 两行

- [ ] **Step 2: 删 `keys.js` 中的 `KEEP_ALIVE`**

`music-party-web/src/constants/keys.js` 删除第 8 行 `KEEP_ALIVE: 'mp_keep_alive',`。

- [ ] **Step 3: 删 `ChatOverlay.vue` 的 `//alive` 分支**

`music-party-web/src/components/ChatOverlay.vue`：
- 删除第 411-417 行的整段：
```js
  // 拦截本地保活指令
  if (text === '//alive') {
    uiStore.toggleKeepAlive();
    success(`Keep-Alive ${uiStore.keepAliveEnabled ? 'ENABLED' : 'DISABLED'}. Please refresh to apply.`);
    inputContent.value = '';
    return;
  }
```
- 删除第 205 行 `import { useUiStore } from '../stores/ui';` 与第 214 行 `const uiStore = useUiStore();`（`uiStore` 仅被 `//alive` 分支使用，已确认无其他引用）。

- [ ] **Step 4: 更新 README**

`README.md`：
- 第 29 行改为：
```markdown
*   **响应式设计**：完美适配 PC 宽屏与移动端；支持媒体会话锁屏控制，移动端推荐『添加到主屏幕』以启用 PWA 后台播放。
```
- 删除第 143 行的 `/alive` 整条 bullet（保活命令已移除）。

- [ ] **Step 5: 构建验证**

Run: `cd music-party-web && npm run build`
Expected: 构建成功。注意此时 `AudioEngine.vue` 仍引用 `ui.keepAliveEnabled`（值为 `undefined`，`v-if` 恒假，不渲染保活元素，不会报错），Task 3 会移除。

- [ ] **Step 6: 提交**

```bash
git add music-party-web/src/stores/ui.js music-party-web/src/constants/keys.js music-party-web/src/components/ChatOverlay.vue README.md
git commit -m "refactor: 移除保活开关与 //alive 命令

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: 前端核心播放改造（不清空 src + 看门狗 + 锁屏进度 + RESYNC）

**Files:**
- Modify: `music-party-web/src/components/AudioEngine.vue`（全量重写：移除保活 + src 不清空 + 新事件绑定）
- Modify: `music-party-web/src/composables/useAudio.js`（全量重写：`@ended`/缓冲看门狗 + `setPositionState` + visibility/online RESYNC）

**Interfaces:**
- Consumes: `player.isLoading`、`player.isPaused`、`player.nowPlaying`、`player.localProgress`、`player.tryReconnect()`、`socketService.send(WS_DEST.RESYNC)`、`playerStore.getCurrentProgress()`
- Produces: `useAudio()` 新增返回 `handleEnded`、`onWaiting`、`onPlaying`（供 `AudioEngine.vue` 模板绑定）

- [ ] **Step 1: 重写 `useAudio.js`**

`music-party-web/src/composables/useAudio.js` 全量替换为：

```js
// src/composables/useAudio.js

import { ref, onMounted, onUnmounted, watch } from 'vue';
import { useToast } from './useToast';
import { socketService } from '../services/socket';
import { WS_DEST } from '../constants/api';

// 阈值常量
const ENDED_RESYNC_DELAY = 2500;       // 当前曲播完但服务器未推新曲时，等待多久后主动重同步
const STALL_TIMEOUT = 8000;            // 持续缓冲超时（ms），超时后重载 + 重同步
const POSITION_STATE_INTERVAL = 1000;  // setPositionState 节流间隔（ms）

export function useAudio(audioRef, playerStore) {
    const localProgress = ref(0);
    const isBuffering = ref(false);
    const retryCount = ref(0);
    const isErrorState = ref(false);
    const { info, error, success } = useToast();
    let syncTimer = null;
    let wakeLock = null;
    let endedTimer = null;
    let endedTrackId = null;
    let stallTimer = null;
    let lastPositionStateAt = 0;

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

    // 尝试播放并处理浏览器拦截
    const safePlay = async () => {
        if (!audioRef.value || !playerStore.nowPlaying) return;

        try {
            await audioRef.value.play();
            isErrorState.value = false;
            updateMediaSession();
            requestWakeLock();
        } catch (e) {
            if (e.name === 'NotAllowedError') {
                console.warn("Autoplay blocked. User interaction required.");
            } else if (e.name !== 'AbortError') {
                console.warn("Play failed:", e);
            }
        }
    };

    // === 1. 监听资源加载 (canplay) ===
    const checkAutoPlay = () => {
        if (!playerStore.nowPlaying) return;
        isBuffering.value = false;
        clearTimeout(endedTimer);   // 新曲已到位，取消 ended 重同步等待
        clearTimeout(stallTimer);

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
            navigator.mediaSession.playbackState = 'paused';
            releaseWakeLock();
        } else {
            safePlay();
            navigator.mediaSession.playbackState = 'playing';
        }
    });

    // === 3. 监听切歌 ===
    watch(() => playerStore.nowPlaying?.music?.id, () => {
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
    });

    // === 4. 错误重试机制 ===
    const handleError = () => {
        if (!playerStore.nowPlaying?.music?.url) return;

        if (audioRef.value && audioRef.value.error && audioRef.value.error.code === 20) return;

        isBuffering.value = false;
        if (retryCount.value >= 3) {
            isErrorState.value = true;
            return;
        }

        retryCount.value++;
        console.log(`Retry audio (${retryCount.value})...`);
        setTimeout(() => {
            if (audioRef.value) {
                audioRef.value.load();
            }
        }, 1500);
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
        playerStore.isBuffering = true;
        clearTimeout(stallTimer);
        stallTimer = setTimeout(() => {
            if (audioRef.value && !playerStore.isPaused) {
                console.warn('[Stall] Buffer timeout, reloading audio...');
                audioRef.value.load();
                playerStore.tryReconnect();
                socketService.send(WS_DEST.RESYNC);
            }
        }, STALL_TIMEOUT);
    };

    const onPlaying = () => {
        playerStore.isBuffering = false;
        clearTimeout(stallTimer);
    };

    // 页面可见性变化监听
    const handleVisibilityChange = async () => {
        if (document.visibilityState === 'visible') {
            if (!playerStore.isPaused) {
                await requestWakeLock();
            }
            playerStore.tryReconnect();
            // 回到前台无条件重同步，立即对齐服务器进度
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

        syncTimer = setInterval(() => {
            if (!playerStore.nowPlaying) {
                localProgress.value = 0;
                return;
            }

            // 1. 获取理论上的正确进度
            const targetTime = playerStore.getCurrentProgress();

            // 2. 更新 UI 绑定值 (localProgress)
            if (audioRef.value && !audioRef.value.paused) {
                localProgress.value = audioRef.value.currentTime * 1000;
            } else {
                localProgress.value = targetTime;
            }

            // 3. 强行同步逻辑 (纠偏)
            if (audioRef.value && !isBuffering.value && !isErrorState.value) {
                if (playerStore.isPaused) {
                    if (Math.abs(audioRef.value.currentTime * 1000 - targetTime) > 200) {
                        audioRef.value.currentTime = targetTime / 1000;
                    }
                } else {
                    const domTime = audioRef.value.currentTime * 1000;
                    const threshold = document.hidden ? 10000 : 2000;
                    if (Math.abs(domTime - targetTime) > threshold) {
                        if (audioRef.value.readyState >= 2) {
                            console.log(`[Sync] Correcting time (${document.hidden ? 'bg' : 'fg'}): ${domTime} -> ${targetTime}`);
                            audioRef.value.currentTime = targetTime / 1000;
                        }
                    }
                }
            }

            // 4. 锁屏/通知栏真实进度（节流，避免每 200ms 都调用）
            const now = Date.now();
            if (now - lastPositionStateAt >= POSITION_STATE_INTERVAL
                && 'mediaSession' in navigator && navigator.mediaSession.setPositionState) {
                lastPositionStateAt = now;
                try {
                    navigator.mediaSession.setPositionState({
                        duration: playerStore.nowPlaying.music.duration / 1000,
                        playbackRate: playerStore.isPaused ? 0 : 1,
                        position: playerStore.localProgress / 1000,
                    });
                } catch (e) {
                    // 某些状态（如无有效时长）会抛异常，忽略
                }
            }
        }, 200);
    });

    onUnmounted(() => {
        document.removeEventListener('visibilitychange', handleVisibilityChange);
        window.removeEventListener('online', handleNetworkChange);
        clearInterval(syncTimer);
        clearTimeout(endedTimer);
        clearTimeout(stallTimer);
        releaseWakeLock();
    });

    return {
        localProgress,
        isBuffering,
        isErrorState,
        retryCount,
        handleError,
        checkAutoPlay,
        handleEnded,
        onWaiting,
        onPlaying
    };
}
```

- [ ] **Step 2: 重写 `AudioEngine.vue`**

`music-party-web/src/components/AudioEngine.vue` 全量替换为（移除保活元素 + src 不清空 + 绑定新事件）：

```vue
<template>
  <div class="hidden">
    <audio
        ref="audioRef"
        :src="audioSrc"
        @error="handleError"
        @waiting="onWaiting"
        @playing="onPlaying"
        @canplay="onCanPlay"
        @seeked="onCanPlay"
        @ended="handleEnded"
        referrerpolicy="no-referrer"
    ></audio>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue';
import { usePlayerStore } from '../stores/player';
import { useUiStore } from '../stores/ui';
import { useAudio } from '../composables/useAudio';

const player = usePlayerStore();
const ui = useUiStore();
const audioRef = ref(null);

const {
  localProgress,
  isBuffering,
  isErrorState,
  handleError,
  checkAutoPlay,
  handleEnded,
  onWaiting,
  onPlaying
} = useAudio(audioRef, player);

// 记住最后一首有效 URL：服务器拉取下一首（nowPlaying=null）的间隙不硬断
const lastGoodUrl = ref('');
watch(() => player.nowPlaying?.music?.url, (url) => {
  if (url) lastGoodUrl.value = url;
});

const audioSrc = computed(() => {
  const current = player.nowPlaying?.music?.url;
  if (current) return current;
  // 服务器仍在加载下一首：继续播上一首，避免间隙中断
  if (player.isLoading && lastGoodUrl.value) return lastGoodUrl.value;
  // 服务器空闲：清空 src，音频自然停止
  return '';
});

// 同步状态到 playerStore
watch(localProgress, (val) => {
  player.localProgress = val;
});
watch(isBuffering, (val) => {
  player.isBuffering = val;
});
watch(isErrorState, (val) => {
  player.isErrorState = val;
});

// 监听音量
watch(() => ui.volume, (newVol) => {
  if (audioRef.value) {
    audioRef.value.volume = newVol;
  }
}, { immediate: true });

const onCanPlay = () => {
  player.isBuffering = false;
  checkAutoPlay();
};

onMounted(() => {
  if (audioRef.value) {
    audioRef.value.volume = ui.volume;
  }
});
</script>
```

- [ ] **Step 3: 构建验证**

Run: `cd music-party-web && npm run build`
Expected: 构建成功，无报错（警告可忽略）。

- [ ] **Step 4: 手动验证（Android 实机）**

1. 起后端 `./mvnw spring-boot:run`（或现有部署）与前端 `cd music-party-web && npm run dev`。
2. Android Chrome 打开，点 CONNECT，播放点歌。
3. 切歌时确认：歌曲之间**无硬中断**（旧曲尾音延续到新曲出现）。
4. 切后台/锁屏 5 分钟，回来后确认：
   - 播放进度与另一台设备/服务器一致（周期广播重锚生效）；
   - 期间若有歌曲结束，自动进入下一首（`@ended` + 周期广播兜底）。
5. 通知栏媒体控制：播放/暂停/下一首可用，**进度条实时走动**（`setPositionState` 生效）。
6. DevTools → Performance → CPU 6× slowdown 模拟节流，确认后台行为仍可恢复。

- [ ] **Step 5: 提交**

```bash
git add music-party-web/src/composables/useAudio.js music-party-web/src/components/AudioEngine.vue
git commit -m "feat: 移动端后台播放加固（不清空src、ended/缓冲看门狗、锁屏进度、RESYNC）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: PWA（manifest + 图标 + meta + 提示）

**Files:**
- Create: `music-party-web/public/manifest.webmanifest`
- Create: `music-party-web/public/icons/icon-192.png`、`icon-512.png`、`apple-touch-icon.png`（脚本生成）
- Modify: `music-party-web/index.html`
- Modify: `music-party-web/src/App.vue`（PWA 提示）

**Interfaces:**
- Consumes: 无
- Produces: 无（独立增强）

- [ ] **Step 1: 生成图标（纯 Python，无依赖）**

在仓库根目录运行（Git Bash，若 `python` 不存在则试 `python3`）：

```bash
python - <<'PY'
import struct, zlib, os

def chunk(tag, data):
    return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)

def make_icon(path, size, rgb):
    # 纯色 RGBA PNG（8-bit，非隔行）
    row = b'\x00' + bytes(rgb) * size
    raw = row * size
    ihdr = struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0)
    png = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', ihdr)
           + chunk(b'IDAT', zlib.compress(raw))
           + chunk(b'IEND', b''))
    with open(path, 'wb') as f:
        f.write(png)
    print('wrote', path)

ACCENT = (249, 115, 22)  # #F97316 主题橙
os.makedirs('music-party-web/public/icons', exist_ok=True)
make_icon('music-party-web/public/icons/icon-192.png', 192, ACCENT)
make_icon('music-party-web/public/icons/icon-512.png', 512, ACCENT)
make_icon('music-party-web/public/icons/apple-touch-icon.png', 180, ACCENT)
PY
```

Expected: 输出三行 `wrote ...`。验证文件大小：`ls -la music-party-web/public/icons/`（各约几十 KB）。

- [ ] **Step 2: 写 `manifest.webmanifest`**

`music-party-web/public/manifest.webmanifest`：

```json
{
  "name": "Music Party",
  "short_name": "MusicParty",
  "description": "和朋友同步听歌的音乐派对",
  "start_url": "/",
  "scope": "/",
  "display": "standalone",
  "background_color": "#F9FAFB",
  "theme_color": "#111827",
  "icons": [
    { "src": "/icons/icon-192.png", "sizes": "192x192", "type": "image/png" },
    { "src": "/icons/icon-512.png", "sizes": "512x512", "type": "image/png" }
  ]
}
```

- [ ] **Step 3: 更新 `index.html`**

在 `music-party-web/index.html` 第 7 行（viewport meta）之后追加：

```html
    <meta name="mobile-web-app-capable" content="yes" />
    <meta name="apple-mobile-web-app-capable" content="yes" />
    <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent" />
    <link rel="manifest" href="/manifest.webmanifest" />
    <link rel="apple-touch-icon" href="/icons/apple-touch-icon.png" />
```

- [ ] **Step 4: App.vue 加 PWA 提示**

`music-party-web/src/App.vue`：
- 第 79 行 `const { register } = useToast();` 改为 `const { register, info } = useToast();`
- `startGame`（第 81-84 行）改为：

```js
const startGame = () => {
  hasStarted.value = true;
  player.connect();
  maybeShowPwaHint();
};

const maybeShowPwaHint = () => {
  try {
    if (localStorage.getItem('mp_pwa_hint_shown')) return;
    if (window.matchMedia('(display-mode: standalone)').matches) return; // 已安装 PWA
    if (!/Android|iPhone|iPad|iPod/i.test(navigator.userAgent)) return;  // 仅移动端
    localStorage.setItem('mp_pwa_hint_shown', '1');
    info('想稳定后台播放？点浏览器菜单 → 添加到主屏幕');
  } catch (e) { /* localStorage 不可用时忽略 */ }
};
```

- [ ] **Step 5: 构建验证**

Run: `cd music-party-web && npm run build`
Expected: 构建成功；`dist/` 中出现 `manifest.webmanifest` 与 `icons/`。

- [ ] **Step 6: 提交**

```bash
git add music-party-web/public/manifest.webmanifest music-party-web/public/icons music-party-web/index.html music-party-web/src/App.vue
git commit -m "feat: 新增 PWA manifest 与安装提示，支持 iOS 后台播放引导

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```
