// ... existing code ...
<template>
  <div class="relative w-full h-full flex items-center justify-center overflow-hidden">

    <!-- LAYER 0: 静态背景层 (最底层) -->
    <div class="absolute inset-0 z-0 pointer-events-none">
      <div class="absolute inset-0 bg-[linear-gradient(rgba(17,24,39,0.03)_1px,transparent_1px),linear-gradient(90deg,rgba(17,24,39,0.03)_1px,transparent_1px)] bg-[size:40px_40px]"></div>
      <div class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 text-[12vw] font-black text-medical-200/40 select-none whitespace-nowrap tracking-tighter blur-sm">
        {{ uiStore.backWords.toUpperCase() }}
      </div>
      <!-- 四角标记 (移动端下移，避开右上角浮动队列按钮) -->
      <div class="absolute top-14 md:top-8 left-8 w-8 h-8 border-t-2 border-l-2 border-medical-300"></div>
      <div class="absolute top-14 md:top-8 right-8 w-8 h-8 border-t-2 border-r-2 border-medical-300"></div>
      <div class="absolute bottom-8 left-8 w-8 h-8 border-b-2 border-l-2 border-medical-300"></div>
      <div class="absolute bottom-8 right-8 w-8 h-8 border-b-2 border-r-2 border-medical-300"></div>
    </div>

    <!-- LAYER 1: 动态视觉层 (Canvas) -->
    <!-- 移动端随封面一起上移，保持背景圆环与封面同心 -->
    <div class="absolute inset-0 z-10 flex items-center justify-center pointer-events-none -translate-y-12 md:translate-y-0">
      <canvas
          ref="canvasRef"
          width="1200"
          height="1200"
          class="absolute left-1/2 top-1/2 -translate-x-1/4 -translate-y-1/3 w-[160vw] h-[160vw] md:w-[1000px] md:h-[1000px]"
      ></canvas>

      <!-- 旋转圈圈 (CSS动画) -->
      <div class="absolute inset-0 w-[320px] h-[320px] m-auto border border-medical-200 rounded-full animate-[spin_10s_linear_infinite] opacity-30 border-dashed"></div>
      <div class="absolute inset-0 w-[340px] h-[340px] m-auto border border-medical-200 rounded-full animate-[spin_15s_linear_infinite_reverse] opacity-20"></div>
    </div>

    <!-- LAYER 2: 信息层 (歌词 & 日志) -->
    <div class="absolute inset-0 z-20 pointer-events-none">
      <!-- 左侧：同步歌词 -->
      <div class="absolute font-mono transition-all duration-300
                  inset-x-0 bottom-14 flex flex-col items-center justify-end h-72 pb-2
                  md:inset-auto md:bottom-8 md:left-10 md:items-start md:justify-end md:h-auto md:w-80
      ">
        <div class="hidden md:block text-[10px] text-accent/80 mb-1 tracking-widest border-b border-accent/30 pb-1 w-16">
          LYRIC_SYSTEM
        </div>
        <div class="w-full space-y-1 text-xs font-normal text-medical-900 leading-tight mix-blend-normal md:mix-blend-multiply md:text-medical-600 flex flex-col md:justify-end min-h-0">
          <div v-if="parsedLyrics.length === 0" class="opacity-50 flex items-center justify-center md:justify-start">
            <span class="text-accent/50 mr-2 text-[10px]">></span>NO_DATA_STREAM
          </div>
          <!-- 移动端歌词单行显示：过长歌词左右滚动（不再换行/顶出边框），桌面端保持换行 -->
          <div
              v-else
              v-for="(line, i) in activeLines"
              :key="`${line.time}-${i}`"
              class="w-full transition-all duration-300"
              :class="i === activeLines.length - 1 ? 'opacity-100 scale-105 md:scale-100 text-medical-900' : 'opacity-40 blur-[0.5px]'"
          >
            <div
                :ref="el => setLineRef(el, i)"
                class="flex items-center overflow-hidden whitespace-nowrap md:whitespace-normal"
                :class="[
                  // 当前行且文字溢出：黑色背景槽固定在容器上（居中，至多占屏幕 70%），文字在槽内滚动
                  i === activeLines.length - 1 && isMobile && isOverflowing(i) ? 'bg-medical-900 text-white' : '',
                  // 溢出行（当前或刚结束）：文字起始贴槽左缘，滚动/定格位置一致；其余行居中（桌面左对齐）
                  isOverflowing(i) ? 'justify-start' : 'justify-center md:justify-start'
                ]"
                :style="isOverflowing(i) ? { maxWidth: `${Math.round(width * 0.7)}px`, width: 'fit-content', marginLeft: 'auto', marginRight: 'auto' } : {}"
            >
              <span class="hidden md:inline text-accent mr-2 text-[10px] flex-shrink-0" :class="{'animate-pulse': i === activeLines.length - 1}">></span>
              <span
                  class="inline-block whitespace-nowrap md:whitespace-normal will-change-transform"
                  :class="[
                    // 已测量且未溢出的当前行：保留紧凑黑底高亮（未测量前不渲染背景，避免闪全幅黑条）
                    {'bg-medical-900 text-white px-1': i === activeLines.length - 1 && isMobile && isShort(i)},
                    {'marquee-scroll': i === activeLines.length - 1 && isOverflowing(i) && isMobile}
                  ]"
                  :style="i === activeLines.length - 1 && isOverflowing(i) ? {
                    animationDuration: marqueeDuration(line.text, line.time),
                    '--mp-scroll-dist': `-${scrollDist(i)}px`   // 首字贴左缘 → 末字右缘贴右缘
                  } : (isOverflowing(i) ? {
                    transform: `translateX(-${scrollDist(i)}px)` // 刚结束的溢出行：无黑底，定格在滚动末尾位置
                  } : {})"
              >{{ line.text }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 右侧：伪系统日志 -->
      <div class="absolute bottom-10 right-10 font-mono text-[10px] text-medical-400 text-right space-y-1 hidden md:block opacity-60">
        <div v-for="(log, i) in logs" :key="i" class="animate-pulse">
          {{ log }} <
        </div>
      </div>
    </div>

    <!-- LAYER 3: 核心实体层 (封面) -->
    <!-- 移动端上移，给底部歌词腾出空间，观感更平衡 -->
    <div class="relative z-30 flex items-center justify-center pointer-events-auto -translate-y-12 md:translate-y-0">
      <div class="relative">
        <div v-if="player.nowPlaying?.enqueuedById" class="absolute -top-4 right-0 text-[10px] font-mono text-accent flex items-center gap-2 z-20 select-none">
          <span>REQ_BY</span>
          <span class="font-bold text-medical-500 border-b border-medical-300 leading-tight">
            {{ userStore.resolveName(player.nowPlaying.enqueuedById, player.nowPlaying.enqueuedByName) }}
          </span>
        </div>

        <div
            id="tutorial-like"
            class="relative w-64 h-64 md:w-72 md:h-72 bg-medical-50 chamfer-br flex items-center justify-center overflow-hidden transition-all duration-500 cursor-pointer border border-white shadow-2xl"
            :class="[
                 // 仅保留暂停时的缩放/灰度
                 player.isPaused ? 'scale-95 grayscale' : 'scale-100',
                 hasLiked ? 'cursor-default' : 'cursor-pointer'
             ]"
            @mouseenter="!isMobile && (isHovering = true)"
            @mouseleave="!isMobile && (isHovering = false)"
            @click="handleCoverClick"
        >
          <!-- Loading 状态 -->
          <div v-if="player.isLoading" class="absolute inset-0 z-50 bg-medical-900/50 backdrop-blur-sm flex flex-col items-center justify-center text-white">
            <div class="w-12 h-12 border-4 border-white/30 border-t-white animate-spin mb-4"></div>
            <span class="font-mono text-xs animate-pulse tracking-widest">FETCHING_AUDIO...</span>
          </div>

          <!-- [MODIFIED START] 交互遮罩层：全息HUD风格 -->
          <Transition
              enter-active-class="transition-all duration-300 ease-out"
              enter-from-class="opacity-0 scale-90"
              enter-to-class="opacity-100 scale-100"
              leave-active-class="transition-all duration-300 ease-in"
              leave-from-class="opacity-100 scale-100"
              leave-to-class="opacity-0 scale-95"
          >
            <div
                v-if="isBursting || (!hasLiked && (isHovering || mobileLikePending)) || hasLiked"
                class="absolute inset-0 z-40 flex items-center justify-center select-none"
                :class="[
                    // 已点赞状态下，只显示极淡的角落标记，不遮挡封面
                    hasLiked && !isBursting ? 'opacity-100' : '',
                    // 交互或爆发时，增加暗色扫描背景
                    (isBursting || (!hasLiked && (isHovering || mobileLikePending))) ? 'bg-medical-900/40' : ''
                ]"
            >
              <!-- 1. 动态网格背景 (仅在交互时显示) -->
              <div v-if="!hasLiked || isHovering" class="absolute inset-0 bg-[linear-gradient(rgba(255,255,255,0.1)_1px,transparent_1px),linear-gradient(90deg,rgba(255,255,255,0.1)_1px,transparent_1px)] bg-[size:20px_20px] opacity-20"></div>

              <!-- 2. 四角瞄准器 (HUD) -->
              <div class="absolute top-2 left-2 w-2 h-2 border-t border-l border-white/50 transition-all duration-300" :class="isBursting ? 'w-4 h-4 border-accent' : ''"></div>
              <div class="absolute top-2 right-2 w-2 h-2 border-t border-r border-white/50 transition-all duration-300" :class="isBursting ? 'w-4 h-4 border-accent' : ''"></div>
              <div class="absolute bottom-2 left-2 w-2 h-2 border-b border-l border-white/50 transition-all duration-300" :class="isBursting ? 'w-4 h-4 border-accent' : ''"></div>
              <div class="absolute bottom-2 right-2 w-2 h-2 border-b border-r border-white/50 transition-all duration-300" :class="isBursting ? 'w-4 h-4 border-accent' : ''"></div>

              <!-- 3. 中央核心交互区 -->
              <div class="relative flex flex-col items-center justify-center gap-2 group">

                <!-- [MODIFIED START] 爆发动画：方形扩散 (去掉 rounded-full, 增加 border) -->
                <!-- -inset-6 确保方形初始大小包裹住文字和图标 -->
                <div v-if="isBursting" class="absolute -inset-6 border border-accent bg-accent/20 animate-ping duration-700 z-0"></div>
                <!-- [MODIFIED END] -->

                <!-- 图标逻辑: 闪电 -->
                <div
                    class="relative transition-all duration-300 transform z-10"
                    :class="[
                      isBursting ? 'scale-125 text-accent drop-shadow-[0_0_15px_rgba(var(--color-accent),0.9)]' :
                      hasLiked ? 'text-accent scale-100 drop-shadow-[0_0_5px_rgba(var(--color-accent),0.5)]' :
                      'text-white/70 scale-100 group-hover:scale-110 group-hover:text-white'
                  ]"
                >
                  <Activity
                      v-if="!hasLiked && (isHovering || mobileLikePending) && !isBursting"
                      class="w-10 h-10 animate-pulse"
                  />

                  <Zap v-else class="w-10 h-10" :class="hasLiked || isBursting ? 'fill-current stroke-none' : ''" />
                </div>


                <!-- 状态文字 -->
                <div class="flex items-center gap-1 font-mono text-[9px] tracking-[0.2em] transition-colors duration-300"
                     :class="isBursting || hasLiked ? 'text-accent' : 'text-white/70'"
                >
                  <span v-if="isBursting">INJECTING...</span>
                  <span v-else-if="hasLiked">WONDERFUL MUSIC</span>
                  <span v-else>LIKE_THIS</span>
                </div>
              </div>
            </div>
          </Transition>

          <img v-if="currentCover" :src="currentCover" class="absolute inset-0 w-full h-full object-cover opacity-80" :class="player.isPaused ? '' : 'animate-[pulse_4s_ease-in-out_infinite]'" />
          <div v-else class="flex flex-col items-center text-medical-300">
            <div class="w-16 h-16 border-2 border-medical-300 mb-2 rotate-45"></div>
            <span class="font-mono text-xs tracking-widest">NO MEDIA</span>
          </div>
          <div class="absolute inset-0 bg-[url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAADCAYAAABS3WWCAAAAE0lEQVQYV2NkYGD4zwABjFAQAwBATgMJy2B8NAAAAABJRU5ErkJggg==')] opacity-20 pointer-events-none z-20"></div>

          <!-- 状态标签：悬停或点赞时隐藏 -->
          <div
              class="absolute top-0 left-0 z-50 px-3 py-1 font-mono text-xs font-bold chamfer-br transition-colors duration-300 bg-medical-900/80 backdrop-blur-sm text-white"
          >
            {{ player.isPaused ? 'PAUSED' : 'PLAYING' }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onUnmounted, computed, watch } from 'vue';
import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import {useEventListener, useWindowSize} from '@vueuse/core';
import { parseLyrics } from '../utils/parser';
import { AudioVisualizer } from '../logic/AudioVisualizer';
import { useUiStore } from '../stores/ui';
import { useLikedSongs } from '../composables/useLikedSongs';
import { Heart, Activity, Zap } from 'lucide-vue-next';

const userStore = useUserStore();
const player = usePlayerStore();
const uiStore = useUiStore();
const { addLikedSong } = useLikedSongs();
const canvasRef = ref(null);
const currentCover = computed(() => player.nowPlaying?.music.coverUrl);
const { width } = useWindowSize();
const isMobile = computed(() => width.value < 768);

// === 交互逻辑 ===
const isHovering = ref(false);       // PC Hover
const mobileLikePending = ref(false);// 移动端第一次点击
const mobileTimer = ref(null);       // 移动端定时器
const isBursting = ref(false);       // 爆发状态（本地+广播）


const hasLiked = computed(() => {
  return player.nowPlaying?.likedUserIds?.includes(userStore.userToken);
});


// 特效冷却 (本地)
const EFFECT_COOLDOWN = 1000;
let lastEffectTime = 0;

const handleCoverClick = () => {
  if (hasLiked.value) return;
  if (isMobile.value) {
    if (!mobileLikePending.value) {
      // 第一次点击
      mobileLikePending.value = true;
      mobileTimer.value = setTimeout(() => {
        mobileLikePending.value = false;
      }, 2000);
    } else {
      // 第二次点击 (确认)
      clearTimeout(mobileTimer.value);
      mobileLikePending.value = false;
      confirmLike();
    }
  } else {
    // PC 直接点击
    confirmLike();
  }
};

const confirmLike = () => {
  player.sendLike();
  // 本地点赞缓存（仅本机，不上报服务器）：供搜索页 LikeSong 列表展示
  if (player.nowPlaying?.music) addLikedSong(player.nowPlaying.music);
  triggerBurst(); // 本地先爆发一次，提升手感
};

const triggerBurst = () => {
  const now = Date.now();
  if (now - lastEffectTime < EFFECT_COOLDOWN) return;
  lastEffectTime = now;

  isBursting.value = true;
  visualizer.impulse(); // 触发 Canvas 圆环爆发
  setTimeout(() => {
    isBursting.value = false;
  }, 500); // 边框高亮持续 0.5s
};

// === 歌词逻辑 ===
const parsedLyrics = ref([]);
const currentLineIndex = ref(-1);

const activeLines = computed(() => {
  const idx = currentLineIndex.value;
  if (parsedLyrics.value.length === 0) return [];
  // 封面上调后底部空间更充裕：移动端显示更多历史行（9行含当前行）
  const historyCount = isMobile.value ? 8 : 10;
  const start = Math.max(0, idx - historyCount);
  const end = Math.min(parsedLyrics.value.length, idx + 1);
  if (idx === -1) return parsedLyrics.value.slice(0, 3);
  return parsedLyrics.value.slice(start, end);
});

// === 长歌词左右滚动（移动端单行）：文字宽度测量 + 溢出/滚动距离即时计算 ===
// 注意：Map 的 key 用 v-for 渲染索引 i（唯一），不能用 line.time —— 歌词开头常有多行
// 时间戳相同（[00:00.000]作词/作曲…），time 重复会导致同一 key 被多个不同宽度的元素交替
// 覆盖，渲染期间写入「每次都是新值」→ 无限重渲染死循环。
const lineEls = new Map();                  // 渲染索引 i -> 行元素（非响应式）
const lineTextWidths = reactive(new Map()); // 渲染索引 i -> 文字固有宽度 px（nowrap 单行宽，与容器宽无关）

function setLineRef(el, i) {
  if (!el) {
    lineEls.delete(i);
    lineTextWidths.delete(i); // 行移出渲染时清理，避免残留
    return;
  }
  lineEls.set(i, el);
  // 取文本 span（容器最后一个子元素）测真实文字宽度——用容器 scrollWidth 的话，
  // 文字不满容器时也会等于容器宽，导致没过长的行也被判为溢出。
  // 文字宽度是单行 nowrap 的固有宽度，任意时机测量结果一致。
  const textEl = el.lastElementChild;
  let w = textEl ? textEl.scrollWidth : el.scrollWidth;
  // 关键：测量值必须排除 span 自身水平 padding（未溢出的当前行会加 px-1=左右各4px）。
  // 否则 px-1 的增删会反向改变测量值：isShort 加 px-1 → scrollWidth+8 → 越过 70% 阈值
  // → 判为溢出去掉 px-1 → 测量回落 → 又判为 short……文字宽落在 [阈值-8, 阈值] 时无限重渲染
  // 死循环（Float 的 "The war outside that kept alive" raw≈205 / pad≈213，阈值≈207-210 时必现，
  // 生产构建不报错、主线程占死）。减去 padding 后测量值恒定，px-1 不再参与反馈。
  if (textEl) {
    const cs = getComputedStyle(textEl);
    w -= (parseFloat(cs.paddingLeft) || 0) + (parseFloat(cs.paddingRight) || 0);
  }
  lineTextWidths.set(i, w);
}

// 溢出不存状态，由「文字宽 > 屏幕横向 70%」即时判定——与槽宽（屏幕 70%）同源，
// 滚动距离因此恒为正、不受渲染时序影响；未测量过的行（首帧）返回 false，不会闪全幅黑条
function isOverflowing(i) {
  return (lineTextWidths.get(i) || 0) > Math.round(width.value * 0.7);
}

// 已测量且未溢出：当前行可保留紧凑黑底高亮
function isShort(i) {
  const tw = lineTextWidths.get(i);
  return tw != null && tw <= Math.round(width.value * 0.7);
}

function scrollDist(i) {
  const tw = lineTextWidths.get(i);
  if (tw == null) return 0;
  // 滚动距离 = 文字宽 - 槽宽（屏幕 70%）：动画从 translateX(0)（首字贴槽左缘）
  // 滑到距离终点（末字右缘贴槽右缘），黑底槽本身不动
  return Math.max(0, tw - Math.round(width.value * 0.7));
}

function marqueeDuration(text, time) {
  // 动画总时长 = 该句展示时长 与 舒适总时长 的较小者：
  //   关键帧里 12% 起始停顿 + 68% 滚动（到 80% 处提前完成）+ 20% 末尾停顿，其余交给 fill forwards 保持，
  //   从而保证切句前一定滚动完毕（短句也不拖到下一句）。
  // 末句无下一句时按 8s 兜底。
  const next = parsedLyrics.value[currentLineIndex.value + 1];
  const lineEnd = next ? next.time : time + 8000;              // 毫秒
  const lineDuration = Math.max(2000, lineEnd - time);         // 该句展示时长，保底 2s
  const comfortable = Math.max(5000, Math.round((text || '').length * 323)); // 按文字长度的舒适总时长
  const duration = Math.max(1500, Math.min(comfortable, lineDuration));
  return `${duration / 1000}s`;
}


watch(() => player.lyricText, (newVal) => {
  parsedLyrics.value = parseLyrics(newVal);
  currentLineIndex.value = -1;
});

// === 系统日志逻辑 (Realtime) ===
const logs = ref(['SYS_INIT: COMPLETED', 'LINK_START: OK']);
const mountTime = Date.now();
let logInterval;
let updateInterval;

const visualizer = new AudioVisualizer();
const isVisualizerActive = computed(() => !!player.nowPlaying && !player.isPaused);

// 监听状态变化以控制 Visualizer
watch(isVisualizerActive, (active) => {
  visualizer.setPlaying(active);
});

// 脱敏工具
const maskId = (id) => id ? `...${id.slice(-4).toUpperCase()}` : 'N/A';
const formatMem = () => {
  if (performance && performance.memory) {
    return Math.floor(performance.memory.usedJSHeapSize / 1048576) + 'MB';
  }
  return 'N/A';
};

const pushLog = (msg) => {
  logs.value.push(msg);
  if (logs.value.length > 6) logs.value.shift();
};

onMounted(() => {
  // 1. 挂载 Canvas & Visualizer
  if (canvasRef.value) {
    visualizer.mount(canvasRef.value);
    visualizer.setPlaying(isVisualizerActive.value);
  }

  // 2. 监听全局自定义事件
  useEventListener(window, 'player:like', () => triggerBurst());

  // 3. 启动高频更新循环 (歌词进度 & 视觉同步)
  updateInterval = setInterval(() => {
    // 歌词进度更新
    if (player.nowPlaying && !player.isPaused && parsedLyrics.value.length > 0) {
      const currentTime = player.getCurrentProgress();
      let activeIdx = -1;
      for (let i = 0; i < parsedLyrics.value.length; i++) {
        if (currentTime >= parsedLyrics.value[i].time) activeIdx = i;
        else break;
      }
      if (activeIdx !== currentLineIndex.value) currentLineIndex.value = activeIdx;
    }
  }, 100);

  // 4. 启动系统状态日志循环 (真实数据)
  logInterval = setInterval(() => {
    if (player.isPaused && Math.random() > 0.4) return;

    const gfx = visualizer.getStatus();
    const stateParams = [
      // 网络与连接
      { cond: true, msg: `UPLINK: ${player.connected ? 'ESTABLISHED' : 'SEARCHING'}` },
      { cond: true, msg: `PEERS_ONLINE: ${userStore.onlineUsers.length}` },
      { cond: true, msg: `STREAM_SYNC: ${player.streamListenerCount} NODES` },
      { cond: true, msg: `SYNC_DELTA: ${Date.now() - player.lastSyncTime > 10000 ? '>10s' : (Date.now() - player.lastSyncTime) + 'ms'}` },
      { cond: true, msg: `NET_ONLINE: ${navigator.onLine ? 'YES' : 'NO'}` },

      // 播放器核心状态
      { cond: player.isLoading, msg: `BUFFER_STATE: LOADING...` },
      { cond: !player.isLoading, msg: `BUFFER_STATE: STABLE` },
      { cond: true, msg: `QUEUE_LEN: ${player.queue.length}` },
      { cond: true, msg: `PLAY_MODE: ${player.playMode}` },
      { cond: parsedLyrics.value.length > 0, msg: `LYRIC_SYNC: ${parsedLyrics.value.length} LINES` },
      { cond: !player.isPaused, msg: `CUR_POS: ${Math.floor(player.getCurrentProgress())}MS` },

      // 媒体信息 (脱敏)
      { cond: !!player.nowPlaying, msg: `MEDIA_HASH: ${maskId(player.nowPlaying?.music?.id)}` },
      { cond: !!player.nowPlaying, msg: `REQ_USER: ${maskId(player.nowPlaying?.enqueuedById)}` },
      { cond: true, msg: `SESSION_ID: ${maskId(userStore.currentUser.sessionId)}` },

      // 用户状态
      { cond: true, msg: `USER_ROLE: ${userStore.isGuest ? 'GUEST' : 'AUTHENTICATED'}` },
      { cond: Object.keys(userStore.bindings).length > 0, msg: `BIND_PLATFORMS: ${Object.keys(userStore.bindings).length}` },

      // 视觉引擎状态
      { cond: true, msg: `GFX_INTENSITY: ${gfx.intensity}` },
      { cond: true, msg: `GFX_RINGS: ${gfx.rings}` },
      { cond: gfx.active, msg: `GFX_ALPHA: ${gfx.alpha}` },

      // 环境与性能
      { cond: !!performance?.memory, msg: `JS_HEAP: ${formatMem()}` },
      { cond: !!performance?.memory, msg: `HEAP_LIMIT: ${Math.floor(performance.memory.jsHeapSizeLimit / 1048576)}MB` },
      { cond: true, msg: `CORE_THREADS: ${navigator.hardwareConcurrency || 'N/A'}` },
      { cond: true, msg: `SCREEN_RES: ${window.screen.width}x${window.screen.height}` },
      { cond: true, msg: `OS_PLATFORM: ${navigator.platform}` },
      { cond: true, msg: `DPR_RATIO: ${window.devicePixelRatio}` },
      { cond: true, msg: `UPTIME: ${Math.floor((Date.now() - mountTime) / 1000)}S` },
      { cond: true, msg: `UI_THEME: ${window.matchMedia('(prefers-color-scheme: dark)').matches ? 'DARK' : 'LIGHT'}` },
      { cond: true, msg: `LANG_SET: ${navigator.language.toUpperCase()}` },
      { cond: true, msg: `TOUCH_NODE: ${navigator.maxTouchPoints > 0 ? 'ACTIVE' : 'NONE'}` },
      { cond: true, msg: `LOCAL_TZ: ${Intl.DateTimeFormat().resolvedOptions().timeZone}` }
    ];

    // 随机抽取一条有意义的状态显示
    const validStates = stateParams.filter(s => s.cond);
    if (validStates.length > 0) {
      const item = validStates[Math.floor(Math.random() * validStates.length)];
      if (!logs.value[logs.value.length - 1]?.includes(item.msg.split(':')[0])) {
         pushLog(item.msg);
      }
    }
  }, 1000);

  // 初始化歌词
  if (player.lyricText) parsedLyrics.value = parseLyrics(player.lyricText);
});

onUnmounted(() => {
  visualizer.unmount();
  clearInterval(logInterval);
  clearInterval(updateInterval);
});
</script>

<style scoped>
/*
 * 移动端长歌词滚动：黑底固定不动，仅文字在槽内滚动。
 * 起点：首字母贴黑底左缘（translateX(0)）；终点：末字母右缘贴黑底右缘（--mp-scroll-dist）。
 * 起始约 12% 停顿 → 滚动到 80% 处提前完成 → 末尾约 20% 停顿，
 * 单次播放 + fill forwards：滚完停在末尾，不循环、不拖到下一句。
 */
@keyframes mp-marquee {
  0%   { transform: translateX(0); }
  12%  { transform: translateX(0); }
  80%  { transform: translateX(var(--mp-scroll-dist, -100%)); }
  100% { transform: translateX(var(--mp-scroll-dist, -100%)); }
}
.marquee-scroll {
  display: inline-block;
  animation: mp-marquee linear 1 forwards;
}
</style>
