# 移动端后台/锁屏播放稳定性设计

日期：2026-08-04
状态：已批准（方案 B：前端加固 + 后端周期广播 + PWA；移除保活音轨）

## 1. 背景与问题

音乐派对网页在移动端（重点：Android Chrome）处于后台或锁屏时，常出现：
- **中断**：切歌间隙音乐硬停
- **卡顿**：缓冲/节流导致的停顿
- **不再播放下一首**：当前曲播完后没有后续
- **失去同步**：进度与服务器漂移

## 2. 根因（调查结论）

架构为「服务器主时钟 + WebSocket 推送切歌」：服务器 `MusicPlayerService.playerLoop()`（每秒）检测歌曲结束并异步拉取下一首（最长 10s），随后通过 STOMP 广播 `nowPlaying`；客户端 `<audio>` 换 src 播放。主要根因：

1. **切歌间隙 `nowPlaying` 置 null → 前端清空 `src` → 硬中断**
   - `MusicPlayerService.playerLoop()` 歌曲结束后 `currentMusic.set(null)`（`MusicPlayerService.java:171`），拉取下一首期间 `getCurrentPlayerState()` 返回 `nowPlaying=null`。
   - 前端 `audioSrc = player.nowPlaying?.music.url || ''`（`AudioEngine.vue:47`）→ src 被清空、音频直接停止。
2. **`<audio>` 无 `@ended` 本地兜底**（`AudioEngine.vue` 仅绑定 error/waiting/playing/canplay/seeked）。WebSocket 断连/丢消息时，当前曲播完即永久停住。
3. **后台心跳被节流 → WebSocket 被判超时断开**。STOMP 心跳 10s 由客户端 setInterval 驱动，后台节流导致发不出心跳，Spring SimpleBroker 约 20-30s 后断开会话。
4. **iOS 后台挂起 + 现有保活 WAV 无效**。保活音轨为 16 字节全零 1ms 静音 WAV，iOS 常判定为非活跃音频；且 `AudioEngine.vue:63` 启动路径未设音量（以 1.0 音量循环近无声"咔哒"声，损害听感）。
5. **同步纯靠外推、无周期校正**。服务器只在事件发生时广播状态；客户端只在收到广播时重锚 `remotePosition`，丢一次广播即漂移到下一首。

## 3. 目标

在 **Android Chrome（主）与 iOS（尽力，PWA 引导）** 上：
- 切歌间隙不中断（消除硬停）
- 当前曲播完能可靠进入下一首（本地兜底）
- 后台/锁屏进度与服务器保持同步（主动防漂）
- 锁屏显示真实进度（Media Session）
- 保活音轨移除，听感干净

## 4. 非目标（YAGNI）

- **不做**服务器提前预载下一首（收益边际、改动 `playNextInQueue` 复杂决策逻辑、风险高）。
- **不改** Wake Lock 现状（锁屏场景下 `screen` 类型会自动释放，无意义）。
- **不做** iOS 纯浏览器下的 100% 后台保证（技术上不可行；用 PWA 引导尽力而为）。

## 5. 详细设计

### 5.1 前端：切歌间隙不清空 src（`AudioEngine.vue`）

`audioSrc` 从 `nowPlaying?.music.url || ''` 改为「记住最后一首有效 URL」：

- `nowPlaying` 有曲目 → 使用新 URL（正常切歌）。
- `nowPlaying` 为 null 且 `isLoading`（服务器拉取下一首中）→ **保留旧 URL 继续播放**，间隙不断。
- 服务器空闲（null 且非 loading）→ `audioRef.pause()` 暂停（用暂停表达停止，而非清空 src，避免 UI 闪烁）。

实现要点：
- 新增 `lastGoodUrl` ref；`watch(() => player.nowPlaying?.music?.url)` 仅在 URL 非空时更新。
- 新增 `shouldStop` 判定：`!player.nowPlaying && !player.isLoading` 时暂停。
- 注意重复播放同 URL（如 repeat-one）场景不破坏现有 `@seeked` 重启机制。

### 5.2 前端：`@ended` + 卡顿看门狗（`useAudio.js` / `AudioEngine.vue`）

**`@ended` 兜底**：音频自然播完但服务器未推新曲时：
- 记录 `endedIdRef = player.nowPlaying?.music?.id`。
- 启动 2.5s 定时器；超时后若仍在同一曲（或 nowPlaying 为 null）→ `playerStore.tryReconnect()` + 发送 `RESYNC`。
- 收到新 `nowPlaying` 或 `@canplay` 时清除定时器。

**卡顿看门狗**：`@waiting` 设置缓冲计时 8s；超时仍缓冲中 → `audioRef.load()` 重载一次 + `RESYNC`。`@playing`/`@canplay` 清除计时。避免无限干等。

### 5.3 前端：回到前台/恢复网络 → 无条件 RESYNC（`useAudio.js`）

`handleVisibilityChange` 回前台时：在现有 `tryReconnect` 基础上**总是发送一次 `RESYNC`**，使 `remotePosition` 立即对齐服务器。断线重连路径保留现有 RESYNC（`socketHandler.js:onConnect` 已发送）。

### 5.4 前端：锁屏进度（Media Session `setPositionState`）

在 `useAudio.js` 同步循环中（节流约 1s 一次）调用：

```
navigator.mediaSession.setPositionState({
  duration: nowPlaying.music.duration / 1000,
  playbackRate: isPaused ? 0 : 1,
  position: audioRef.currentTime,
})
```

使 Android 通知栏/锁屏进度条真实走动。需 `if ('setPositionState' in navigator.mediaSession)` 保护。

### 5.5 后端：周期轻量状态广播（`MusicPlayerService.java`）

新增 `@Scheduled`（默认 5000ms，配置项 `app.player.sync-broadcast-interval-ms`）：

```
@Scheduled(fixedRateString = "${app.player.sync-broadcast-interval-ms:5000}")
public void broadcastSyncHeartbeat() {
    if (currentMusic.get() == null && isPaused.get()) return; // 空闲跳过
    broadcastFullPlayerState();
}
```

效果：
- 客户端每次收到广播即重锚 `remotePosition` → 主动防漂移。
- 客户端可借「长时间收不到广播」识别假连接 → 主动重连。
- 空闲时跳过，节省资源。<20 人在线，全量状态每 5s 无压力。

### 5.6 PWA 引导

- 新增 `public/manifest.webmanifest`：name/short_name、icons（192/512 PNG）、`start_url`、`display: standalone`、theme/background color。
- `index.html` 增加：`<link rel="manifest">`、`apple-mobile-web-app-capable`、`mobile-web-app-capable`、`apple-mobile-web-app-status-bar-style`、apple-touch-icon。
- 用纯 Python 脚本（`zlib`+`struct`）生成 192/512 PNG 图标与 apple-touch-icon，无额外依赖。
- 移动端首屏一次性提示「点浏览器菜单 → 添加到主屏幕，可获得稳定后台播放」；仅提示不强求。复用现有 Toast 组件。

### 5.7 清理：移除保活音轨

- `AudioEngine.vue`：删除 `<audio ref="aliveAudioRef">` 元素、`ALIVE_WAV`、`aliveAudioRef`、isPaused watch 中的保活分支、`onMounted` 中的保活启动。
- `stores/ui.js`：删除 `keepAliveEnabled`、`toggleKeepAlive`。
- `constants/keys.js`：删除 `KEEP_ALIVE`。
- `ChatOverlay.vue`：删除 `//alive` 命令分支。
- README：更新 `/alive` 文档条目。

## 6. 配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `app.player.sync-broadcast-interval-ms` | 5000 | 周期状态广播间隔 |

前端看门狗阈值（切歌 2.5s / 缓冲 8s）暂为常量，不配置化。

## 7. 测试

- **后端**：为周期广播加单元测试——空闲时（无曲+暂停）不广播；有曲时广播 `PlayerStateEvent`。
- **前端**：无测试框架；Android 实机 + DevTools（Performance 面板模拟 CPU 节流、`Page.setWebLifecycleState` 模拟 frozen）手动验证：
  - 切歌间隙音乐不断
  - 断 WebSocket 后当前曲播完能通过 RESYNC 恢复下一首
  - 后台 5 分钟进度与服务器对齐
  - 锁屏进度条真实走动

## 8. 风险与回退

- **周期广播频率过高** → 全量状态构建含队列状态查询，若队列很大（上限 1000）可能稍重；通过配置项调低间隔或空闲跳过缓解。<20 人默认 5s 无压力。
- **不清空 src 的语义变化** → 停止播放改为 pause 而非清 src；需回归验证「停止」状态 UI 表现。
- **iOS 纯浏览器后台仍可能被挂起** → 属于平台限制，PWA 引导为缓解手段，不回退。

## 9. 涉及文件清单

后端：
- `src/main/java/org/thornex/musicparty/service/MusicPlayerService.java`
- `src/main/resources/application.yml`（配置项）
- 测试：`src/test/java/org/thornex/musicparty/service/MusicPlayerServiceStateTest.java`（新增用例）

前端：
- `music-party-web/src/components/AudioEngine.vue`
- `music-party-web/src/composables/useAudio.js`
- `music-party-web/src/stores/ui.js`
- `music-party-web/src/constants/keys.js`
- `music-party-web/src/components/ChatOverlay.vue`
- `music-party-web/index.html`
- `music-party-web/public/manifest.webmanifest`（新增）
- `music-party-web/public/icons/*.png`（新增，脚本生成）
- README.md
