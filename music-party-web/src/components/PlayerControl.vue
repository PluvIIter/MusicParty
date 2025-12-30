<template>
  <div class="h-24 bg-white border-t border-medical-200 flex items-center px-4 md:px-8 relative z-50 shadow-lg">
    <!-- 音频元素 -->
    <!-- 增加 v-if="audioSrc" 防止空链接报错 -->
    <!-- 增加 @canplay 用于拦截自动播放 -->
    <audio
      ref="audioRef" 
      :src="audioSrc" 
      autoplay 
      @ended="handleEnded" 
      @error="handleError" 
      @waiting="isBuffering = true" 
      @playing="isBuffering = false"
      @canplay="checkAutoPlay"
      referrerpolicy="no-referrer"
    ></audio>

    <!-- 封面 -->
    <div class="w-16 h-16 md:w-20 md:h-20 -mt-6 md:mt-0 shadow-lg border-2 border-white chamfer-br flex-shrink-0 relative z-10 bg-medical-800">
      <CoverImage :src="nowPlaying?.music.coverUrl" class="w-full h-full" />
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
          <p class="text-xs font-mono truncate transition-colors duration-300"
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
           <span v-if="isBuffering" class="animate-pulse text-accent">BUFFERING...</span>
           <span v-else>{{ formatTime(localProgress) }} / {{ formatTime(nowPlaying?.music.duration || 0) }}</span>
        </div>
      </div>

      <!-- 进度条 -->
      <div class="h-1 bg-medical-200 w-full relative">
        <div 
          class="h-full bg-accent transition-all duration-300 ease-linear relative"
          :style="{ width: progressPercent + '%' }"
        >
           <div class="absolute right-0 top-1/2 -translate-y-1/2 w-2 h-2 bg-accent rotate-45"></div>
        </div>
      </div>
      
      <!-- 移动端简易控制 -->
      <div class="flex md:hidden justify-end gap-3 mt-2">
        <button
            @click="player.toggleShuffle"
            :disabled="!player.connected"
            class="p-2 border rounded-sm disabled:opacity-50 transition-colors"
            :class="player.isShuffle
                ? 'bg-accent text-white border-accent'
                : 'bg-medical-50 border-medical-200 text-medical-500'"
        >
          <Shuffle class="w-4 h-4" />
        </button>
        <button @click="downloadCurrentMusic" class="p-2 bg-medical-50 border border-medical-200 rounded-sm text-medical-500 active:bg-medical-200">
          <Download class="w-4 h-4" />
        </button>
         <button @click="player.togglePause" class="p-2 bg-medical-100 rounded-sm">
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
        <button @click="player.toggleShuffle" :class="player.isShuffle ? 'text-accent' : 'text-medical-400'" title="Shuffle">
            <Shuffle class="w-5 h-5" />
        </button>

        <!-- 新增：下载按钮 (放在 Shuffle 旁边或者 Next 后面) -->
        <button @click="downloadCurrentMusic" class="text-medical-400 hover:text-accent transition-colors" title="Download">
          <Download class="w-5 h-5" />
        </button>
        
        <button 
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
          <VolumeX v-if="volume === 0" class="w-5 h-5" />
          <Volume1 v-else-if="volume < 0.5" class="w-5 h-5" />
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
                :style="{ width: (volume * 100) + '%' }"
            >
              <!-- 装饰滑块 (只在悬停时显示) -->
              <div class="absolute right-0 top-1/2 -translate-y-1/2 w-2 h-3 bg-medical-900 group-hover:bg-accent transition-colors scale-0 group-hover:scale-100"></div>
            </div>
          </div>
        </div>

        <div class="w-8 text-[10px] font-mono text-medical-400 text-right">
          {{ Math.round(volume * 100) }}%
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch, onMounted, onUnmounted } from 'vue';
import { Download } from 'lucide-vue-next';
import { usePlayerStore } from '../stores/player';
import { Play, Pause, SkipForward, Shuffle, Volume2, Volume1, VolumeX } from 'lucide-vue-next';
import CoverImage from './CoverImage.vue';
import { useToast } from '../composables/useToast';
import dayjs from 'dayjs';

const player = usePlayerStore();
const audioRef = ref(null);
const localProgress = ref(0);
const isBuffering = ref(false);
const toast = useToast();
const { info, error } = useToast();
const volumeTrackRef = ref(null);
const isDraggingVolume = ref(false);

// 音量状态
const volume = ref(parseFloat(localStorage.getItem('mp_volume') || '0.5'));
const lastVolume = ref(0.5);

// 核心数据引用
const nowPlaying = computed(() => player.nowPlaying);

const audioSrc = computed(() => {
  if (!nowPlaying.value) return "";
  return nowPlaying.value.music.url;
});

const updateMediaSession = () => {
  if ('mediaSession' in navigator) {
    if (player.nowPlaying) {
      const music = player.nowPlaying.music;
      navigator.mediaSession.metadata = new MediaMetadata({
        title: music.name,
        artist: music.artists.join(' / '),
        album: 'Music Party',
        artwork: [{ src: music.coverUrl, sizes: '512x512', type: 'image/png' }]
      });
      navigator.mediaSession.playbackState = 'playing';
    } else {
      // 🟢 没歌的时候，设为 paused 而不是直接清空
      navigator.mediaSession.playbackState = 'paused';
    }
  }
};

// 监听歌曲变化
watch(() => player.nowPlaying?.music?.id, () => {
  updateMediaSession();
}, { immediate: true });

// 增强监听逻辑
watch(audioSrc, async (newVal) => {
  if (!newVal) return;

  await nextTick();
  if (audioRef.value) {
    // 强制告诉浏览器：媒体流发生了变化，请保持存活
    audioRef.value.load();
    if (!player.isPaused) {
      audioRef.value.play().catch(e => {
        console.warn("后台播放被拦截，可能需要引导用户前台点击一次", e);
      });
    }
  }
});

const progressPercent = computed(() => {
    if (!nowPlaying.value || nowPlaying.value.music.duration === 0) return 0;
    return Math.min(100, (localProgress.value / nowPlaying.value.music.duration) * 100);
});

const formatTime = (ms) => {
    if(!ms) return "00:00";
    return dayjs(ms).format('mm:ss');
};

const handleEnded = () => {
};

const handleError = (e) => {
    if (!audioSrc.value) return; // 忽略空链接错误
    console.error("Audio Error:", e.target.error);
};

// --- 音量逻辑 ---
const toggleMute = () => {
    if (volume.value > 0) {
        lastVolume.value = volume.value;
        volume.value = 0;
    } else {
        volume.value = lastVolume.value > 0 ? lastVolume.value : 0.5;
    }
};

// 监听音量变化
watch(volume, (newVal) => {
    localStorage.setItem('mp_volume', newVal);
    if (audioRef.value) {
        audioRef.value.volume = newVal;
    }
});

const updateVolumeByMouse = (e) => {
  if (!volumeTrackRef.value) return;

  const rect = volumeTrackRef.value.getBoundingClientRect();
  // 计算鼠标距离轨道左侧的距离
  const x = e.clientX - rect.left;
  // 限制在 0 到 rect.width 之间，然后转为 0-1 的比例
  const percentage = Math.max(0, Math.min(1, x / rect.width));
  volume.value = parseFloat(percentage.toFixed(2));
};

// 鼠标按下
const handleVolumeMouseDown = (e) => {
  isDraggingVolume.value = true;
  updateVolumeByMouse(e); // 按下时立即跳转音量

  // 绑定全局事件，这样鼠标移出轨道也能继续拖拽
  window.addEventListener('mousemove', handleVolumeMouseMove);
  window.addEventListener('mouseup', handleVolumeMouseUp);
};

const handleVolumeMouseMove = (e) => {
  if (isDraggingVolume.value) {
    updateVolumeByMouse(e);
  }
};

const handleVolumeMouseUp = () => {
  isDraggingVolume.value = false;
  window.removeEventListener('mousemove', handleVolumeMouseMove);
  window.removeEventListener('mouseup', handleVolumeMouseUp);
};

// 记得清理
onUnmounted(() => {
  window.removeEventListener('mousemove', handleVolumeMouseMove);
  window.removeEventListener('mouseup', handleVolumeMouseUp);
});

// --- 自动播放与状态同步逻辑 ---

// 1. 拦截自动播放
// 当音频准备好时，如果全局是暂停状态，强制暂停
const checkAutoPlay = () => {
    isBuffering.value = false;
    // 修复点：这里必须使用 player.isPaused，不能用 newPaused
    if (player.isPaused && audioRef.value) {
        console.log("State is paused, preventing autoplay.");
        audioRef.value.pause();
    }
    // 同时应用音量
    if (audioRef.value) {
        audioRef.value.volume = volume.value;
    }
};

// 2. 监听暂停状态变化
// 这里参数名为 newPaused，所以在内部可以使用 newPaused
watch(() => player.isPaused, (newPaused) => {
    if (audioRef.value) {
        if (newPaused) {
            audioRef.value.pause();
        } else {
            if (audioSrc.value) {
                audioRef.value.play().catch(e => console.log("Autoplay prevented", e));
            }
        }
    }
});

// 3. 监听切歌 (Src 变化)
watch(audioSrc, () => {
    // 延迟检查，确保 DOM 更新
    setTimeout(() => {
        // 修复点：这里必须使用 player.isPaused，不能用 newPaused
        if (player.isPaused && audioRef.value) {
            audioRef.value.pause();
        }
        // 切歌后重新应用音量
        if (audioRef.value) {
             audioRef.value.volume = volume.value;
        }
    }, 100);
});

// --- 进度条同步逻辑 ---
let syncTimer;

onMounted(() => {
    syncTimer = setInterval(() => {
        if(!nowPlaying.value) { 
            localProgress.value = 0;
            return;
        }

        const backendTime = player.getCurrentProgress(); 
        const domTime = (audioRef.value?.currentTime || 0) * 1000;
        const duration = nowPlaying.value.music.duration;

        // 防止时间溢出
        if (duration > 0 && backendTime > duration) {
            localProgress.value = duration;
            return;
        }

        localProgress.value = player.isPaused ? domTime : backendTime;

        // 同步时间
        if (!player.isPaused && Math.abs(domTime - backendTime) > 2000) {
            if (duration > 0 && backendTime < duration) {
                if(audioRef.value) {
                    audioRef.value.currentTime = backendTime / 1000;
                }
            }
        }
    }, 500);
});

onUnmounted(() => clearInterval(syncTimer));

const downloadCurrentMusic = async () => {
  if (!nowPlaying.value) return;

  const music = nowPlaying.value.music;
  const url = music.url;
  const filename = `${music.name} - ${music.artists[0]}.mp3`;

  info(`Starting download: ${music.name}...`);

  try {
    // 使用 fetch 获取文件流，强制触发下载
    const response = await fetch(url);
    if (!response.ok) throw new Error('Network response was not ok');

    const blob = await response.blob();
    const blobUrl = window.URL.createObjectURL(blob);

    const link = document.createElement('a');
    link.href = blobUrl;
    link.download = filename;
    document.body.appendChild(link);
    link.click();

    //TC理
    document.body.removeChild(link);
    window.URL.revokeObjectURL(blobUrl);
  } catch (e) {
    console.error("Download failed", e);
    // 如果 fetch 失败（可能是严重的跨域限制），尝试回退到 window.open
    window.open(url, '_blank');
    error('Download via blob failed, opening in new tab.');
  }
};
</script>