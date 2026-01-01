// ... existing code ...
<template>
  <div class="relative w-full h-full flex items-center justify-center overflow-hidden">

    <!-- LAYER 0: 静态背景层 (最底层) -->
    <div class="absolute inset-0 z-0 pointer-events-none">
      <div class="absolute inset-0 bg-[linear-gradient(rgba(17,24,39,0.03)_1px,transparent_1px),linear-gradient(90deg,rgba(17,24,39,0.03)_1px,transparent_1px)] bg-[size:40px_40px]"></div>
      <div class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 text-[12vw] font-black text-medical-200/40 select-none whitespace-nowrap tracking-tighter blur-sm">
        THORNEX
      </div>
      <!-- 四角标记 -->
      <div class="absolute top-8 left-8 w-8 h-8 border-t-2 border-l-2 border-medical-300"></div>
      <div class="absolute top-8 right-8 w-8 h-8 border-t-2 border-r-2 border-medical-300"></div>
      <div class="absolute bottom-8 left-8 w-8 h-8 border-b-2 border-l-2 border-medical-300"></div>
      <div class="absolute bottom-8 right-8 w-8 h-8 border-b-2 border-r-2 border-medical-300"></div>
    </div>

    <!-- LAYER 1: 动态视觉层 (Canvas) -->
    <div class="absolute inset-0 z-10 flex items-center justify-center pointer-events-none">
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
                  inset-x-0 bottom-7 flex flex-col items-center justify-end h-64 pb-2
                  md:inset-auto md:bottom-8 md:left-10 md:items-start md:justify-end md:h-auto md:w-80
      ">
        <div class="hidden md:block text-[10px] text-accent/80 mb-1 tracking-widest border-b border-accent/30 pb-1 w-16">
          LYRIC_SYSTEM
        </div>
        <div class="w-full space-y-1 text-xs font-normal text-medical-900 leading-tight mix-blend-normal md:mix-blend-multiply md:text-medical-600 flex flex-col md:justify-end min-h-0">
          <div v-if="parsedLyrics.length === 0" class="opacity-50 flex items-center justify-center md:justify-start">
            <span class="text-accent/50 mr-2 text-[10px]">></span>NO_DATA_STREAM
          </div>
          <div
              v-else
              v-for="(line, i) in activeLines"
              :key="line.time"
              class="transition-all duration-300 flex items-center md:justify-start justify-center"
              :class="i === activeLines.length - 1 ? 'opacity-100 scale-105 md:scale-100 text-medical-900' : 'opacity-40 blur-[0.5px]'"
          >
            <span class="hidden md:inline text-accent mr-2 text-[10px]" :class="{'animate-pulse': i === activeLines.length - 1}">></span>
            <span :class="{'bg-medical-900 text-white px-1': i === activeLines.length - 1 && isMobile}">
                     {{ line.text }}
            </span>
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
    <div class="relative z-30 flex items-center justify-center pointer-events-auto">
      <div class="relative">
        <div v-if="player.nowPlaying?.enqueuedById" class="absolute -top-4 right-0 text-[10px] font-mono text-medical-400 flex items-center gap-2 z-20 select-none">
          <span>REQ_BY</span>
          <span class="font-bold text-medical-500 border-b border-medical-300 leading-tight">
            {{ userStore.resolveName(player.nowPlaying.enqueuedById, player.nowPlaying.enqueuedByName) }}
          </span>
        </div>

        <div class="relative w-64 h-64 md:w-72 md:h-72 bg-medical-50 chamfer-br border border-white shadow-2xl flex items-center justify-center overflow-hidden transition-transform duration-700"
             :class="player.isPaused ? 'scale-95 grayscale' : 'scale-100'"
        >
          <div v-if="player.isLoading" class="absolute inset-0 z-50 bg-medical-900/50 backdrop-blur-sm flex flex-col items-center justify-center text-white">
            <div class="w-12 h-12 border-4 border-white/30 border-t-white animate-spin mb-4"></div>
            <span class="font-mono text-xs animate-pulse tracking-widest">FETCHING_AUDIO...</span>
          </div>

          <img v-if="currentCover" :src="currentCover" class="absolute inset-0 w-full h-full object-cover opacity-80" :class="player.isPaused ? '' : 'animate-[pulse_4s_ease-in-out_infinite]'" />
          <div v-else class="flex flex-col items-center text-medical-300">
            <div class="w-16 h-16 border-2 border-medical-300 mb-2 rotate-45"></div>
            <span class="font-mono text-xs tracking-widest">NO MEDIA</span>
          </div>
          <div class="absolute inset-0 bg-[url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAADCAYAAABS3WWCAAAAE0lEQVQYV2NkYGD4zwABjFAQAwBATgMJy2B8NAAAAABJRU5ErkJggg==')] opacity-20 pointer-events-none z-20"></div>
          <div class="absolute top-0 left-0 z-30 bg-medical-900/80 backdrop-blur-sm text-white px-3 py-1 font-mono text-xs font-bold chamfer-br">
            {{ player.isPaused ? 'PAUSED' : 'PLAYING' }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed, watch } from 'vue';
import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import { useWindowSize } from '@vueuse/core';
import { parseLyrics } from '../utils/parser';
import { AudioVisualizer } from '../logic/AudioVisualizer'; // 引入新逻辑类

const userStore = useUserStore();
const player = usePlayerStore();
const canvasRef = ref(null);
const currentCover = computed(() => player.nowPlaying?.music.coverUrl);
const { width } = useWindowSize();
const isMobile = computed(() => width.value < 768);

// === 歌词逻辑 ===
const parsedLyrics = ref([]);
const currentLineIndex = ref(-1);

const activeLines = computed(() => {
  const idx = currentLineIndex.value;
  if (parsedLyrics.value.length === 0) return [];
  const historyCount = isMobile.value ? 5 : 10;
  const start = Math.max(0, idx - historyCount);
  const end = Math.min(parsedLyrics.value.length, idx + 1);
  if (idx === -1) return parsedLyrics.value.slice(0, 3);
  return parsedLyrics.value.slice(start, end);
});


watch(() => player.lyricText, (newVal) => {
  parsedLyrics.value = parseLyrics(newVal); // 修正: 原代码 parseLrc 写错了，应该是 utils 里的 parseLyrics
  currentLineIndex.value = -1;
});

// === 伪日志 ===
const logs = ref(['SYNC_RATE: 100%', 'AUDIO_STREAM: STABLE']);
let logInterval;

const isVisualizerActive = computed(() => {
  return !!player.nowPlaying && !player.isPaused;
});

const visualizer = new AudioVisualizer();
let updateInterval; // 用于更新歌词进度，不涉及 Canvas

// 监听状态变化
watch(isVisualizerActive, (active) => {
  visualizer.setPlaying(active);
});

onMounted(() => {
  // 1. 挂载 Canvas
  if (canvasRef.value) {
    visualizer.mount(canvasRef.value);
    visualizer.setPlaying(isVisualizerActive.value);
  }

  // 2. 歌词与日志更新逻辑 (保留)
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
  }, 100); // 100ms 检查一次歌词即可，不需要 RAF

  logInterval = setInterval(() => {
    if (!player.isPaused) {
      const hex = Math.floor(Math.random() * 16777215).toString(16).toUpperCase();
      logs.value.push(`DATA_PACKET: 0x${hex}`);
      if (logs.value.length > 5) logs.value.shift();
    }
  }, 2000);

  if (player.lyricText) parsedLyrics.value = parseLyrics(player.lyricText);
});

onUnmounted(() => {
  visualizer.unmount();
  clearInterval(logInterval);
  clearInterval(updateInterval);
});
</script>