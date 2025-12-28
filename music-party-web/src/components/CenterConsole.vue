// File Path: music-party-web\src\components\CenterConsole.vue

<template>
  <div class="relative w-full h-full flex items-center justify-center overflow-hidden">

    <!--
      ========================================
      LAYER 0: 静态背景层 (最底层)
      包含：网格、巨大文字、四角标记
      ========================================
    -->
    <div class="absolute inset-0 z-0 pointer-events-none">
      <!-- 网格背景 -->
      <div class="absolute inset-0 bg-[linear-gradient(rgba(17,24,39,0.03)_1px,transparent_1px),linear-gradient(90deg,rgba(17,24,39,0.03)_1px,transparent_1px)] bg-[size:40px_40px]"></div>

      <!-- 巨大的背景文字 -->
      <div class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 text-[12vw] font-black text-medical-200/40 select-none whitespace-nowrap tracking-tighter blur-sm">
        THORNEX
      </div>

      <!-- 四角标记 -->
      <div class="absolute top-8 left-8 w-8 h-8 border-t-2 border-l-2 border-medical-300"></div>
      <div class="absolute top-8 right-8 w-8 h-8 border-t-2 border-r-2 border-medical-300"></div>
      <div class="absolute bottom-8 left-8 w-8 h-8 border-b-2 border-l-2 border-medical-300"></div>
      <div class="absolute bottom-8 right-8 w-8 h-8 border-b-2 border-r-2 border-medical-300"></div>
    </div>

    <!--
      ========================================
      LAYER 1: 动态视觉层 (中间层)
      包含：Canvas (橙色圆环/频谱)、虚线装饰圈
      Z-Index: 10
      ========================================
    -->
    <div class="absolute inset-0 z-10 flex items-center justify-center pointer-events-none">
      <!--
         [配置说明 - Canvas位置与大小]
         1. width/height="1200": 画布分辨率，越大越清晰，也越能容纳大半径圆环。
         2. CSS w-[...] h-[...]: 屏幕上的显示尺寸。
            - 移动端 w-[160vw]: 放大到超出屏幕，让圆环看起来更宏大。
            - PC端 md:w-[1000px]: 适度大小。
         3. 混合模式: mix-blend-screen 或 normal 配合内部 alpha 使用。
      -->
      <canvas
          ref="canvasRef"
          width="1200"
          height="1200"
          class="absolute left-1/2 top-1/2 -translate-x-1/4 -translate-y-1/3 w-[160vw] h-[160vw] md:w-[1000px] md:h-[1000px]"
      ></canvas>

      <!-- 旋转圈圈 (虚线装饰) -->
      <div class="absolute inset-0 w-[320px] h-[320px] m-auto border border-medical-200 rounded-full animate-[spin_10s_linear_infinite] opacity-30 border-dashed"></div>
      <div class="absolute inset-0 w-[340px] h-[340px] m-auto border border-medical-200 rounded-full animate-[spin_15s_linear_infinite_reverse] opacity-20"></div>
    </div>

    <!--
      ========================================
      LAYER 2: 信息层
      包含：歌词、伪系统日志
      Z-Index: 20 (位于背景之上，但在封面之下)
      ========================================
    -->
    <div class="absolute inset-0 z-20 pointer-events-none">
      <!-- 左侧：同步歌词 -->
      <div class="absolute font-mono transition-all duration-300
                  /* Mobile: 底部偏上，确保被 Layer 3 的封面遮挡时有层次感 */
                  inset-x-0 bottom-7 flex flex-col items-center justify-end h-64 pb-2
                  /* PC: 左下角 */
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

    <!--
      ========================================
      LAYER 3: 核心实体层 (最顶层)
      包含：唱片封面、状态标签
      Z-Index: 30 (确保覆盖歌词和圆环)
      ========================================
    -->
    <div class="relative z-30 flex items-center justify-center pointer-events-auto">
      <div class="relative">
        <!-- 点歌人信息 -->
        <div v-if="player.nowPlaying?.enqueuedById" class="absolute -top-4 right-0 text-[10px] font-mono text-medical-400 flex items-center gap-2 z-20 select-none">
          <span>REQ_BY</span>
          <span class="font-bold text-medical-500 border-b border-medical-300 leading-tight">{{ userStore.resolveName(player.nowPlaying.enqueuedById) }}</span>
        </div>

        <!-- 封面本体 -->
        <div class="relative w-64 h-64 md:w-72 md:h-72 bg-medical-50 chamfer-br border border-white shadow-2xl flex items-center justify-center overflow-hidden transition-transform duration-700"
             :class="player.isPaused ? 'scale-95 grayscale' : 'scale-100'"
        >
          <img
              v-if="currentCover"
              :src="currentCover"
              class="absolute inset-0 w-full h-full object-cover opacity-80"
              :class="player.isPaused ? '' : 'animate-[pulse_4s_ease-in-out_infinite]'"
          />
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
import {ref, onMounted, onUnmounted, computed, watch} from 'vue';
import {usePlayerStore} from '../stores/player';
import {useWindowSize} from '@vueuse/core';
import { useUserStore } from '../stores/user'; // 🟢 引入 userStore

const userStore = useUserStore();
const player = usePlayerStore();
const canvasRef = ref(null);
const currentCover = computed(() => player.nowPlaying?.music.coverUrl);
const {width} = useWindowSize();
const isMobile = computed(() => width.value < 768);

// === 歌词逻辑 (保持不变) ===
const parsedLyrics = ref([]);
const currentLineIndex = ref(-1);
const timeExp = /\[(\d{2,}):(\d{2})(?:\.(\d{2,3}))?\]/;
const parseLrc = (lrc) => {
  if (!lrc) return [];
  const lines = lrc.split('\n');
  const result = [];
  for (let line of lines) {
    const match = timeExp.exec(line);
    if (match) {
      const min = parseInt(match[1]);
      const sec = parseInt(match[2]);
      const ms = match[3] ? parseInt(match[3].padEnd(3, '0')) : 0;
      const time = min * 60 * 1000 + sec * 1000 + ms;
      const text = line.replace(timeExp, '').trim();
      if (text) {
        result.push({time, text});
      }
    }
  }
  return result;
};

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
  parsedLyrics.value = parseLrc(newVal);
  currentLineIndex.value = -1;
});

// === 伪日志 ===
const logs = ref(['SYNC_RATE: 100%', 'AUDIO_STREAM: STABLE']);

// === Canvas 绘图核心逻辑 ===
let animationId;
let logInterval;
let ctx;

const breatheBars = 120;
const breatheRadiusBase = 180;

// ==========================================
// [配置说明 - 橙色圆环]
// ==========================================
// radius: 圆环基础半径 (基于画布1200分辨率). 增大此值可放大圆环整体.
// baseWidth: 圆环最窄处的宽度.
// maxWidth: 波动时的最大增量宽度.
// speed: 旋转速度. 绝对值越大转越快, 正负号代表方向.
// offset: 相位偏移.
// segments: 波峰数量.
const rings = [
  // 层1：极大，慢速逆时针，3段
  { radius: 450, baseWidth: 5, maxWidth: 150, speed: -0.015, offset: 0, segments: 3 },
  // 层2：极大，中速顺时针，4段
  { radius: 450, baseWidth: 10, maxWidth: 100, speed: 0.02, offset: 2, segments: 4 },
  // 层3：极大，快速顺时针，5段
  { radius: 450, baseWidth: 8, maxWidth: 80, speed: 0.03, offset: 4, segments: 5 }
];

let rippleTime = 0;
let breatheOffset = 0;

// 平滑过渡变量 (Lerp)
let smoothAlpha = 0.05; // 当前透明度
let smoothWidthScale = 0.3; // 当前宽度缩放系数

const loop = () => {
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

  if (canvasRef.value) {
    const canvas = canvasRef.value;
    ctx = canvas.getContext('2d');
    const center = canvas.width / 2;

    ctx.clearRect(0, 0, canvas.width, canvas.height);

    // --- 状态计算与平滑过渡 (Lerp) ---
    const isPlaying = player.nowPlaying && !player.isPaused;

    // 1. 时间流速
    if (isPlaying) {
      rippleTime += 0.5; // 播放时：正常流速
    } else {
      rippleTime += 0.1; // 暂停时：极慢蠕动
    }

    // 2. 目标透明度与目标宽度
    // 播放时：透明度较高(0.25)，宽度全开(1.0)
    // 暂停时：透明度极低(0.05)，宽度收缩(0.3)
    const targetAlpha = isPlaying ? 0.25 : 0.05;
    const targetWidthScale = isPlaying ? 1.0 : 0.3;

    // 3. 执行线性插值 (0.05 是平滑系数，越小越慢)
    smoothAlpha += (targetAlpha - smoothAlpha) * 0.03;
    smoothWidthScale += (targetWidthScale - smoothWidthScale) * 0.05;


    // 🟢 PART 1: 橙色流体圆环
    ctx.save();
    ctx.globalCompositeOperation = 'screen'; // 重叠发光
    ctx.shadowBlur = 50;
    ctx.shadowColor = '#F97316';

    rings.forEach((ring) => {
      ctx.beginPath();
      const count = 240;

      // 动态计算当前最大宽度：基础maxWidth * 平滑缩放系数
      const currentMaxWidth = ring.maxWidth * smoothWidthScale;

      // 外圈
      for (let i = 0; i <= count; i++) {
        const angle = (i / count) * Math.PI * 2;
        const wave = Math.sin(angle * ring.segments + rippleTime * ring.speed + ring.offset);
        const normalizedWave = (wave + 1) / 2;

        const currentWidth = ring.baseWidth + normalizedWave * currentMaxWidth;

        const r = ring.radius + currentWidth / 2;
        const x = center + Math.cos(angle) * r;
        const y = center + Math.sin(angle) * r;

        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      }

      // 内圈
      for (let i = count; i >= 0; i--) {
        const angle = (i / count) * Math.PI * 2;
        const wave = Math.sin(angle * ring.segments + rippleTime * ring.speed + ring.offset);
        const normalizedWave = (wave + 1) / 2;
        const currentWidth = ring.baseWidth + normalizedWave * currentMaxWidth;

        const r = ring.radius - currentWidth / 2;
        const x = center + Math.cos(angle) * r;
        const y = center + Math.sin(angle) * r;

        ctx.lineTo(x, y);
      }

      ctx.closePath();
      // 使用平滑过渡后的透明度
      ctx.fillStyle = `rgba(249, 115, 22, ${smoothAlpha})`;
      ctx.fill();
    });
    ctx.restore();

    // 🟢 PART 2: 呼吸态频谱 (前景灰色，保持不变)
    ctx.globalCompositeOperation = 'source-over';
    breatheOffset += 0.05;
    for (let i = 0; i < breatheBars; i++) {
      const angle = (Math.PI * 2 * i) / breatheBars;
      const h = Math.sin(i * 0.5 + Date.now() / 500) * 5 + 5;

      const startX = center + Math.cos(angle) * (breatheRadiusBase + 10);
      const startY = center + Math.sin(angle) * (breatheRadiusBase + 10);
      const endX = center + Math.cos(angle) * (breatheRadiusBase + 10 + h);
      const endY = center + Math.sin(angle) * (breatheRadiusBase + 10 + h);

      ctx.beginPath();
      ctx.moveTo(startX, startY);
      ctx.lineTo(endX, endY);
      ctx.strokeStyle = '#D1D5DB';
      ctx.lineWidth = 2;
      ctx.lineCap = 'round';
      ctx.stroke();
    }
  }

  animationId = requestAnimationFrame(loop);
};

onMounted(() => {
  loop();
  logInterval = setInterval(() => {
    if (!player.isPaused) {
      const hex = Math.floor(Math.random() * 16777215).toString(16).toUpperCase();
      logs.value.push(`DATA_PACKET: 0x${hex}`);
      if (logs.value.length > 5) logs.value.shift();
    }
  }, 2000);
  if (player.lyricText) parsedLyrics.value = parseLrc(player.lyricText);
});

onUnmounted(() => {
  cancelAnimationFrame(animationId);
  clearInterval(logInterval);
});
</script>