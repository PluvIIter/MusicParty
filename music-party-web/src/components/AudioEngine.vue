<template>
  <div class="hidden">
    <!-- 主播放器 -->
    <audio
        ref="audioRef"
        :src="audioSrc"
        @error="handleError"
        @waiting="player.isBuffering = true"
        @playing="player.isBuffering = false"
        @canplay="onCanPlay"
        referrerpolicy="no-referrer"
    ></audio>

    <!-- 静默保活音轨 (Keep-Alive) -->
    <audio
        ref="silentAudioRef"
        :src="SILENT_WAV"
        loop
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
const silentAudioRef = ref(null);

// 极简 1秒 静默 WAV
const SILENT_WAV = 'data:audio/wav;base64,UklGRigAAABXQVZFRm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQQAAAAAAA==';

const {
  localProgress,
  isBuffering,
  isErrorState,
  handleError,
  checkAutoPlay
} = useAudio(audioRef, player);

const audioSrc = computed(() => player.nowPlaying?.music.url || '');

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

// 监听播放状态以维持静默音轨
watch(() => player.isPaused, (paused) => {
  if (!paused) {
    silentAudioRef.value?.play().catch(() => {});
  } else {
    silentAudioRef.value?.pause();
  }
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
  // 初始尝试播放静默音轨
  if (!player.isPaused) {
    silentAudioRef.value?.play().catch(() => {});
  }
});
</script>