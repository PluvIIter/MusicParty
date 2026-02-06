<!-- src/components/layout/MainLayout.vue -->
<script setup>
import { ref } from 'vue';
import { Search, Users, ListMusic, X, Minimize2, Maximize2, Music, Volume2 } from 'lucide-vue-next';
import UserList from '../UserList.vue';
import QueueList from '../QueueList.vue';
import { useUserStore } from '../../stores/user';
import { useUiStore } from '../../stores/ui';
import { usePlayerStore } from '../../stores/player';

const emit = defineEmits(['search']);
const userStore = useUserStore();
const uiStore = useUiStore();
const playerStore = usePlayerStore();

const authorName = import.meta.env.VITE_APP_AUTHOR_NAME || 'ThorNex';

const mobileQueueOpen = ref(false);
const mobileUserOpen = ref(false);

const toggleMobileQueue = () => {
  mobileQueueOpen.value = !mobileQueueOpen.value;
  if(mobileQueueOpen.value) mobileUserOpen.value = false;
};

const toggleMobileUser = () => {
  mobileUserOpen.value = !mobileUserOpen.value;
  if(mobileUserOpen.value) mobileQueueOpen.value = false;
};

const handleSearchClick = () => {
  emit('search');
}
</script>

<template>
  <div class="h-[100dvh] w-screen flex flex-col relative overflow-hidden bg-medical-50">
    <!-- 1. 顶部栏 Header -->
    <header v-if="!uiStore.isLiteMode" class="h-14 bg-white border-b border-medical-200 flex justify-between items-center px-4 md:px-6 flex-shrink-0 relative z-50">
      <div class="font-black text-xl tracking-tighter text-medical-900 flex items-center gap-2">
        <div class="w-3 h-3 bg-accent"></div>
        <span class="text-lg md:text-xl">MUSIC PARTY</span>
        <span class="text-medical-300 font-mono font-normal text-xs">by {{ authorName }}</span>
      </div>

      <div class="flex items-center gap-4">
        <!-- 移动端：显示人数按钮 -->
        <button
            id="tutorial-rename-mobile"
            @click="toggleMobileUser"
            class="md:hidden relative flex items-center justify-center w-9 h-9 bg-medical-50 border border-medical-200 text-medical-500 hover:text-medical-900 transition-colors overflow-hidden group rounded-sm transform-gpu"
            :class="{ 'bg-medical-200 text-medical-900 border-medical-300': mobileUserOpen }"
        >
          <span
              class="absolute inset-0 flex items-center justify-center font-black text-4xl leading-none text-accent/15 pointer-events-none z-0 select-none scale-110 font-mono"
          >
            {{ userStore.onlineUsers.length > 9 ? 'N' : userStore.onlineUsers.length }}
          </span>
          <Users class="w-5 h-5 relative z-10" />
        </button>

        <!-- 精简模式按钮 -->
        <button
            @click="uiStore.toggleLiteMode"
            class="flex items-center justify-center w-9 h-9 md:w-10 md:h-9 border border-medical-200 bg-medical-50 hover:bg-medical-100 text-medical-600 transition-all rounded-sm"
            title="精简模式"
        >
          <Minimize2 class="w-4 h-4" />
        </button>

        <!-- 搜索按钮 -->
        <button id="tutorial-search" @click="handleSearchClick" class="flex items-center justify-center w-9 h-9 md:w-auto md:h-9 md:px-4 border border-medical-200 bg-medical-50 hover:bg-medical-100 font-bold text-sm text-medical-600 transition-all rounded-sm gap-2">
          <Search class="w-4 h-4" />
          <span class="hidden md:inline">SEARCH</span>
        </button>
      </div>
    </header>

    <!-- 2. 主体内容区 -->
    <div v-if="!uiStore.isLiteMode" class="flex-1 flex overflow-hidden relative">
      <aside class="w-64 bg-medical-50 border-r border-medical-200 hidden md:block overflow-y-auto">
        <UserList />
      </aside>

      <main class="flex-1 bg-medical-100/30 relative flex flex-col overflow-hidden z-10">
        <slot></slot>
      </main>

      <aside class="w-80 bg-white border-l border-medical-200 hidden md:block overflow-hidden">
        <QueueList />
      </aside>

      <div class="md:hidden absolute top-4 right-4 z-40">
        <button id="tutorial-queue-mobile" @click="toggleMobileQueue" class="p-2 bg-white shadow border border-medical-200 rounded-sm">
          <ListMusic class="w-5 h-5 text-medical-600"/>
        </button>
      </div>

      <Transition name="slide-fade">
        <div v-if="mobileQueueOpen" class="md:hidden absolute inset-0 bg-white z-30 pt-4 overflow-y-auto">
          <div class="px-4 pb-2 border-b border-medical-100 mb-2 flex justify-between">
            <span class="text-xs font-mono text-medical-400">QUEUE</span>
            <button @click="mobileQueueOpen = false"><X class="w-4 h-4"/></button>
          </div>
          <QueueList />
        </div>
      </Transition>

      <Transition name="slide-fade">
        <div v-if="mobileUserOpen" class="md:hidden absolute inset-0 bg-medical-50 z-30 pt-4 overflow-y-auto">
          <div class="px-4 pb-2 border-b border-medical-200 mb-2 flex justify-between">
            <span class="text-xs font-mono text-medical-400">OPERATIVES</span>
            <button @click="mobileUserOpen = false"><X class="w-4 h-4"/></button>
          </div>
          <UserList />
        </div>
      </Transition>
    </div>

    <!-- 3. 精简模式视图 -->
    <div v-else class="flex-1 flex flex-col items-center justify-center bg-medical-50 p-6">
      <div class="flex flex-col items-center gap-8 max-w-md w-full">
        <div class="relative">
          <div class="w-24 h-24 bg-white border border-medical-200 flex items-center justify-center shadow-sm">
            <Music class="w-12 h-12 text-accent" :class="{ 'animate-bounce': !playerStore.isPaused }" />
          </div>
          <div v-if="!playerStore.isPaused" class="absolute -inset-4 border border-accent/10 animate-ping pointer-events-none"></div>
        </div>

        <div class="flex flex-col items-center gap-2">
          <span class="text-[10px] font-mono text-medical-400 tracking-[0.3em]">NOW_PLAYING</span>
          <h2 class="text-2xl md:text-3xl font-black text-medical-900 tracking-tighter text-center line-clamp-2">
            {{ playerStore.nowPlaying?.music.name || 'SYSTEM_IDLE' }}
          </h2>
          <span v-if="playerStore.nowPlaying" class="text-sm font-bold text-accent">
            {{ playerStore.nowPlaying.music.artists.join(' / ') }}
          </span>
        </div>

        <!-- 音量控制 (精简模式) -->
        <div class="flex items-center gap-4 w-full max-w-[240px] bg-white p-4 border border-medical-200 shadow-sm rounded-sm">
          <Volume2 class="w-4 h-4 text-medical-500 flex-shrink-0" />
          <input
              type="range"
              min="0"
              max="1"
              step="0.01"
              v-model.number="uiStore.volume"
              class="flex-1 accent-accent h-1 bg-medical-100 rounded-lg appearance-none cursor-pointer"
          />
          <span class="font-mono text-[10px] text-medical-500 w-8 text-right">{{ Math.round(uiStore.volume * 100) }}%</span>
        </div>

        <button
            @click="uiStore.toggleLiteMode"
            class="group flex items-center gap-3 px-8 py-3 bg-medical-900 text-white font-bold rounded-sm transition-all hover:bg-accent hover:scale-105 active:scale-95"
        >
          <Maximize2 class="w-5 h-5" />
          <span class="tracking-widest text-sm">退出精简模式</span>
        </button>
      </div>
    </div>

    <slot v-if="!uiStore.isLiteMode" name="player"></slot>
  </div>
</template>

<style scoped>
.slide-fade-enter-active, .slide-fade-leave-active { transition: all 0.3s ease; }
.slide-fade-enter-from, .slide-fade-leave-to { transform: translateY(10px); opacity: 0; }

/* 简单的 range 样式优化 */
input[type=range]::-webkit-slider-thumb {
  -webkit-appearance: none;
  height: 12px;
  width: 12px;
  border-radius: 0;
  background: #111827; /* medical-900 */
  cursor: pointer;
  margin-top: -4px;
}
input[type=range]:hover::-webkit-slider-thumb {
  background: #ff5722; /* accent */
}
input[type=range]::-webkit-slider-runnable-track {
  width: 100%;
  height: 4px;
  cursor: pointer;
  background: #f3f4f6; /* medical-100 */
}
</style>
