<!-- src/App.vue -->
<template>
  <!-- 全局 Toast 挂载点 -->
  <ToastNotification ref="toastInstance" />

  <div class="h-screen w-screen overflow-hidden font-sans">
    <AudioEngine />
    <!-- 1. 认证遮罩 -->
    <AuthOverlay @unlocked="userStore.isAuthPassed = true" v-if="!userStore.isAuthPassed" />

    <!-- 2. 启动页 (Start Screen) -->
    <!-- 注意：点击 Connect 后，我们先不销毁它，直到 socket 连接成功，或者直接切换布局 -->
    <div v-if="userStore.isAuthPassed && !hasStarted" class="absolute inset-0 z-[100] bg-medical-50 flex flex-col items-center justify-center space-y-8">
      <div class="text-4xl font-black tracking-tighter text-medical-900">MUSIC PARTY</div>
      <div class="font-mono text-xs text-medical-400 tracking-widest">SYSTEM READY</div>
      <button
          @click="startGame"
          class="px-12 py-4 bg-medical-900 text-white font-bold text-xl hover:bg-accent transition-colors chamfer-br"
      >
        CONNECT
      </button>
    </div>

    <!-- 3. 主界面 (当 hasStarted 为 true 时显示) -->
    <MainLayout v-if="hasStarted" @search="handleSearchClick">
      <!-- 中间插槽: 视觉控制台 -->
      <CenterConsole />

      <!-- 底部插槽: 播放器 -->
      <!-- 注意：这里不使用 v-if，而是 v-show，或者因为在 MainLayout 里是 slot，
           只有 MainLayout 渲染了，它才会渲染。
           关键是 useAudio 里的逻辑已经修好了，会自动处理播放。
      -->
      <template #player>
        <PlayerControl />
      </template>
    </MainLayout>

    <!-- 4. 全局弹窗 -->
    <SearchModal :isOpen="showSearch" @close="showSearch = false" />
    <NamePromptModal />
    <ChatOverlay v-if="hasStarted && !uiStore.isLiteMode" />
    <TutorialOverlay v-if="hasStarted && !uiStore.isLiteMode" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { useEventListener } from '@vueuse/core';
import { usePlayerStore } from './stores/player';
import { useUserStore } from './stores/user';
import { useUiStore } from './stores/ui';
import { useToast } from './composables/useToast';

// Components
import MainLayout from './components/layout/MainLayout.vue';
import CenterConsole from './components/CenterConsole.vue';
import PlayerControl from './components/PlayerControl.vue';
import AudioEngine from './components/AudioEngine.vue';
import AuthOverlay from './components/AuthOverlay.vue';
import SearchModal from './components/SearchModal.vue';
import NamePromptModal from './components/NamePromptModal.vue';
import ChatOverlay from './components/ChatOverlay.vue';
import ToastNotification from './components/ToastNotification.vue';
import TutorialOverlay from './components/TutorialOverlay.vue';

const player = usePlayerStore();
const userStore = useUserStore();
const uiStore = useUiStore();
const hasStarted = ref(false);
const showSearch = ref(false);
const toastInstance = ref(null);
const { register } = useToast();

const startGame = () => {
  hasStarted.value = true;
  player.connect();
};

// 自动性能优化：切后台自动进入精简模式
useEventListener(document, 'visibilitychange', () => {
  if (document.visibilityState === 'hidden' && hasStarted.value && !player.isPaused && uiStore.autoLiteMode) {
    uiStore.isLiteMode = true;
  }
});

const handleSearchClick = () => {
  // 简单的搜索逻辑代理
  if (userStore.isGuest) {
    userStore.setPostNameAction(() => { showSearch.value = true; });
    userStore.showNameModal = true;
  } else {
    showSearch.value = true;
  }
};

onMounted(() => {
  if (toastInstance.value) register(toastInstance.value);
});
</script>