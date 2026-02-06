<template>
  <div class="h-24 bg-white border-t border-medical-200 flex items-center px-4 md:px-8 relative z-50 shadow-lg">
    <!-- 封面 -->
    <div
        id="tutorial-source"
        @click="openSourcePage"
        class="w-16 h-16 md:w-20 md:h-20 -mt-6 md:mt-0 shadow-lg border-2 border-white chamfer-br flex-shrink-0 relative z-10 bg-medical-800 cursor-pointer group overflow-hidden"
        title="Open Source Page"
    >
      <CoverImage :src="nowPlaying?.music.coverUrl" class="w-full h-full transition-transform duration-300 group-hover:scale-110 group-hover:opacity-50" />

      <!-- 悬浮时的遮罩和图标 -->
      <div class="absolute inset-0 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity duration-300 bg-black/40">
        <ExternalLink class="w-6 h-6 text-white" />
      </div>
    </div>

    <!-- 中间：信息与进度 -->
    <div class="flex-1 ml-4 mr-4 md:mr-8 flex flex-col justify-center min-w-0">
      <div class="flex justify-between items-end mb-1">
        <div class="overflow-hidden w-full">
          <!-- 标题显示逻辑与样式 -->
          <h2 class="text-lg font-bold truncate leading-tight transition-colors duration-300"
              :class="!player.connected ? 'text-orange-600 animate-pulse' : 'text-medical-900'"
          >
            {{
              !player.connected
                  ? '!CONNECTION LOST!'
                  : (nowPlaying ? nowPlaying.music.name : 'WAITING FOR SIGNAL...')
            }}
          </h2>

          <!-- 副标题显示逻辑与样式 -->
          <p class="text-xs font-sans truncate transition-colors duration-300"
             :class="!player.connected ? 'text-orange-500 animate-pulse' : 'text-medical-800/60'"
          >
            {{
              !player.connected
                  ? 'RECONNECT SERVER...'
                  : (nowPlaying ? nowPlaying.music.artists.join(' / ') : 'SYSTEM STANDBY')
            }}
          </p>
        </div>

        <!-- 时间显示 -->
        <div class="hidden md:block font-mono text-xs text-medical-800/60 flex-shrink-0 ml-2">
           <span v-if="player.isLoading" class="text-accent animate-pulse">SYNCING SERVER...</span>
           <span v-if="player.isBuffering" class="animate-pulse text-accent">BUFFERING...</span>
           <span v-else>{{ formatDuration(player.localProgress) }} / {{ formatDuration(nowPlaying?.music.duration || 0) }}</span>
        </div>
      </div>

      <!-- 进度条 -->
      <div class="h-1 bg-medical-200 w-full relative">

        <div
            v-for="(marker, index) in likeMarkers"
            :key="index"
            class="absolute z-20 transition-all duration-300 transform -translate-y-1/2 top-1/2"
            :style="{ left: (marker / (nowPlaying?.music.duration || 1)) * 100 + '%' }"
        >
          <Zap
              class="w-3 h-3 drop-shadow-md "
              :class="'text-accent fill-accent'"
          />
        </div>

        <div
            class="h-full transition-all duration-300 ease-linear relative"
            :class="player.isErrorState ? 'bg-red-500' : 'bg-accent'"
            :style="{ width: progressPercent + '%' }"
        >
          <div
              v-if="!player.isErrorState"
              class="absolute right-0 top-1/2 -translate-y-1/2 w-2 h-2 rotate-45 transition-all duration-300 bg-accent"
          ></div>
        </div>
      </div>
      
      <!-- 移动端简易控制 -->
      <div class="flex md:hidden justify-end gap-3 mt-2">
        <button
            id="tutorial-random-mobile"
            @click="player.toggleShuffle"
            :disabled="!player.connected"
            class="p-2 border rounded-sm disabled:opacity-50 transition-colors"
            :class="player.isShuffle
                ? 'bg-accent text-white border-accent'
                : 'bg-medical-50 border-medical-200 text-medical-500'"
        >
          <Shuffle class="w-4 h-4" />
        </button>
        <button id="tutorial-download-mobile" @click="downloadCurrentMusic" class="p-2 bg-medical-50 border border-medical-200 rounded-sm text-medical-500 active:bg-medical-200">
          <Download class="w-4 h-4" />
        </button>
         <button id="tutorial-pause-mobile" @click="player.togglePause" class="p-2 bg-medical-100 rounded-sm">
             <Play v-if="player.isPaused" class="w-4 h-4" />
             <Pause v-else class="w-4 h-4" />
         </button>
         <button @click="player.playNext" class="p-2 bg-medical-100 rounded-sm">
             <SkipForward class="w-4 h-4" />
         </button>
      </div>
    </div>

    <!-- PC端：右侧控制区 -->
    <div class="hidden md:flex items-center gap-6 flex-shrink-0">
      
      <!-- 播放控制 -->
      <div class="flex items-center gap-4 border-r border-medical-200 pr-6">
        <button id="tutorial-random" @click="player.toggleShuffle" :class="player.isShuffle ? 'text-accent' : 'text-medical-400'" title="Shuffle">
            <Shuffle class="w-5 h-5" />
        </button>

        <!-- 新增：下载按钮 (放在 Shuffle 旁边或者 Next 后面) -->
        <button id="tutorial-download" @click="downloadCurrentMusic" class="text-medical-400 hover:text-accent transition-colors" title="Download">
          <Download class="w-5 h-5" />
        </button>
        
        <button 
            id="tutorial-pause"
            @click="player.togglePause" 
            class="w-10 h-10 bg-medical-900 text-white flex items-center justify-center hover:bg-accent transition-colors chamfer-tl"
        >
            <Play v-if="player.isPaused" class="w-4 h-4 fill-current" />
            <Pause v-else class="w-4 h-4 fill-current" />
        </button>

        <button @click="player.playNext" class="text-medical-800 hover:text-accent transition-colors" title="Next">
            <SkipForward class="w-6 h-6 fill-current" />
        </button>
      </div>

      <!-- 音量控制 -->
      <div class="flex items-center gap-2 group">
        <button @click="toggleMute" class="text-medical-500 hover:text-medical-900 transition-colors">
          <VolumeX v-if="ui.volume === 0" class="w-5 h-5" />
          <Volume1 v-else-if="ui.volume < 0.5" class="w-5 h-5" />
          <Volume2 v-else class="w-5 h-5" />
        </button>

        <!-- 滑块容器 -->
        <div
            ref="volumeTrackRef"
            class="w-24 h-6 flex items-center relative cursor-pointer touch-none"
            @mousedown="handleVolumeMouseDown"
        >
          <!-- 灰色轨道 -->
          <div class="w-full h-1 bg-medical-200 relative">
            <!-- 橙色填充层 -->
            <div
                class="h-full bg-medical-500 group-hover:bg-accent transition-colors relative"
                :style="{ width: (ui.volume * 100) + '%' }"
            >
              <!-- 装饰滑块 (只在悬停时显示) -->
              <div class="absolute right-0 top-1/2 -translate-y-1/2 w-2 h-3 bg-medical-900 group-hover:bg-accent transition-colors scale-0 group-hover:scale-100"></div>
            </div>
          </div>
        </div>

        <div class="w-8 text-[10px] font-mono text-medical-400 text-right">
          {{ Math.round(ui.volume * 100) }}%
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onUnmounted } from 'vue';
import { usePlayerStore } from '../stores/player';
import { useUiStore } from '../stores/ui';
import { formatDuration } from '../utils/format';
import { Download, Shuffle, SkipForward, Play, Pause, Volume2, Volume1, VolumeX, ExternalLink, Zap } from 'lucide-vue-next';
import CoverImage from './CoverImage.vue';
import { useToast } from '../composables/useToast';

const player = usePlayerStore();
const ui = useUiStore();
const volumeTrackRef = ref(null);
const { info, error } = useToast();

const nowPlaying = computed(() => player.nowPlaying);
const likeMarkers = computed(() => nowPlaying.value?.likeMarkers || []);

// 计算进度百分比
const progressPercent = computed(() => {
  if (!nowPlaying.value || nowPlaying.value.music.duration === 0) return 0;
  return Math.min(100, (player.localProgress / nowPlaying.value.music.duration) * 100);
});

// --- 音量逻辑 ---
const lastVolume = ref(0.5);
const isDraggingVolume = ref(false);

const toggleMute = () => {
  if (ui.volume > 0) {
    lastVolume.value = ui.volume;
    ui.setVolume(0);
  } else {
    ui.setVolume(lastVolume.value > 0 ? lastVolume.value : 0.5);
  }
};

// 音量拖拽
const updateVolumeByMouse = (e) => {
  if (!volumeTrackRef.value) return;
  const rect = volumeTrackRef.value.getBoundingClientRect();
  const x = e.clientX - rect.left;
  const percentage = Math.max(0, Math.min(1, x / rect.width));
  ui.setVolume(parseFloat(percentage.toFixed(2)));
};

const handleVolumeMouseDown = (e) => {
  isDraggingVolume.value = true;
  updateVolumeByMouse(e);
  window.addEventListener('mousemove', handleVolumeMouseMove);
  window.addEventListener('mouseup', handleVolumeMouseUp);
};
const handleVolumeMouseMove = (e) => { if (isDraggingVolume.value) updateVolumeByMouse(e); };
const handleVolumeMouseUp = () => {
  isDraggingVolume.value = false;
  window.removeEventListener('mousemove', handleVolumeMouseMove);
  window.removeEventListener('mouseup', handleVolumeMouseUp);
};

// --- 下载逻辑 ---
const downloadCurrentMusic = async () => {
  if (!nowPlaying.value) return;
  const music = nowPlaying.value.music;
  info(`Starting download: ${music.name}...`);
  try {
    const response = await fetch(music.url);
    if (!response.ok) throw new Error('Network error');
    const blob = await response.blob();
    const blobUrl = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = blobUrl;
    link.download = `${music.name} - ${music.artists[0]}.mp3`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    window.URL.revokeObjectURL(blobUrl);
  } catch (e) {
    window.open(music.url, '_blank');
    error('Blob download failed, opening new tab.');
  }
};

// 跳转源页面
const openSourcePage = () => {
  if (!nowPlaying.value) return;
  const { platform, id } = nowPlaying.value.music;
  let url = platform === 'netease' ? `https://music.163.com/#/song?id=${id}` : `https://www.bilibili.com/video/${id}`;
  if (url) window.open(url, '_blank');
};

onUnmounted(() => {
  window.removeEventListener('mousemove', handleVolumeMouseMove);
  window.removeEventListener('mouseup', handleVolumeMouseUp);
});
</script>
