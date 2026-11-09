<template>
  <div class="hidden">
    <audio
        ref="audioRef"
        :src="audioSrc"
        @error="handleError"
        @waiting="onWaiting"
        @playing="onPlaying"
        @progress="onProgress"
        @canplay="onCanPlay"
        @seeked="onCanPlay"
        @ended="handleEnded"
        referrerpolicy="no-referrer"
    ></audio>
  </div>
  <button v-if="needsUserGesture || isErrorState" type="button" @click="retryLocally"
      class="fixed bottom-28 left-1/2 -translate-x-1/2 z-[250] max-w-[90vw] px-5 py-3 bg-medical-900 text-white text-sm font-bold shadow-lg">
    {{ needsUserGesture ? '点此开启声音' : '声音加载失败，点此重试' }}
  </button>
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
  needsUserGesture,
  recoveredUrl,
  handleError,
  checkAutoPlay,
  handleEnded,
  onWaiting,
  onPlaying,
  onProgress,
  retryLocally
} = useAudio(audioRef, player);

// 记住最后一首有效 URL：服务器拉取下一首（nowPlaying=null）的间隙不硬断
const lastGoodUrl = ref('');
watch(() => player.nowPlaying?.music?.url, (url) => {
  if (url) lastGoodUrl.value = url;
});

const audioSrc = computed(() => {
  const current = player.nowPlaying?.music?.url;
  if (current) return recoveredUrl.value || current;
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
