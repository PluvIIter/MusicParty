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

    <!-- 极弱音轨保活 (Keep-Alive) -->
    <audio
        v-if="ui.keepAliveEnabled"
        ref="aliveAudioRef"
        :src="ALIVE_WAV"
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
const aliveAudioRef = ref(null);

// 极弱底噪 WAV (人耳难以察觉)
const ALIVE_WAV = 'data:audio/wav;base64,UklGRjIAAABXQVZFRm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YRAAAAAAAAAAAAAAAAD//w==';

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

// 监听播放状态以维持保活音轨
watch(() => player.isPaused, (paused) => {
  if (!paused && ui.keepAliveEnabled) {
    aliveAudioRef.value?.play().catch(() => {});
  } else {
    aliveAudioRef.value?.pause();
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
  // 初始尝试播放保活音轨
  if (!player.isPaused && ui.keepAliveEnabled) {
    if (aliveAudioRef.value) {
      aliveAudioRef.value.volume = 0.001;
      aliveAudioRef.value.play().catch(() => {});
    }
  }
});
</script>