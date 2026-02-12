<!-- src/components/layout/MainLayout.vue -->
<script setup>
import { ref, onMounted } from 'vue';
import { Search, Users, ListMusic, X, Minimize2, Maximize2, Volume2, Activity } from 'lucide-vue-next';
import UserList from '../UserList.vue';
import QueueList from '../QueueList.vue';
import CoverImage from '../CoverImage.vue';
import { useUserStore } from '../../stores/user';
import { useUiStore } from '../../stores/ui';
import { usePlayerStore } from '../../stores/player';

const emit = defineEmits(['search']);
const userStore = useUserStore();
const uiStore = useUiStore();
const playerStore = usePlayerStore();

onMounted(() => {
  uiStore.fetchConfig();
});

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
      <div class="flex items-center gap-2 flex-shrink-0">
        <div class="w-2.5 h-2.5 md:w-3 md:h-3 bg-accent flex-shrink-0"></div>
        <div class="flex items-baseline gap-1">
          <a href="https://github.com/PluvIIter/MusicParty" target="_blank" class="font-black text-base md:text-xl tracking-tighter text-medical-900 whitespace-nowrap hover:text-accent transition-colors">MUSIC PARTY</a>
          <span class="text-medical-300 font-mono font-normal text-[10px] md:text-xs whitespace-nowrap">by {{ uiStore.authorName }}</span>
        </div>
      </div>

      <div class="flex items-center gap-2 md:gap-4">
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
        <button id="tutorial-search" @click="handleSearchClick" class="flex items-center justify-center w-9 h-9 md:w-auto md:h-9 md:px-4 border border-accent bg-accent hover:bg-accent-hover font-bold text-sm text-white transition-all rounded-sm gap-2">
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
    <div v-else class="flex-1 flex flex-col items-center justify-center bg-medical-50 relative overflow-hidden p-6">
      <!-- 背景装饰 -->
      <div class="absolute inset-0 z-0 pointer-events-none opacity-40">
        <div class="absolute inset-0 bg-[linear-gradient(rgba(17,24,39,0.03)_1px,transparent_1px),linear-gradient(90deg,rgba(17,24,39,0.03)_1px,transparent_1px)] bg-[size:32px_32px]"></div>
        <div class="absolute top-0 left-0 w-full h-full bg-[repeating-linear-gradient(45deg,transparent,transparent_20px,rgba(0,0,0,0.02)_20px,rgba(0,0,0,0.02)_21px)]"></div>
      </div>

      <div class="relative z-10 w-full max-w-lg flex flex-col items-center gap-10">
        <!-- 头部状态 (文案简化) -->
        <div class="flex items-center gap-3 text-[10px] text-medical-400 tracking-[0.2em] uppercase font-sans">
          <Activity class="w-3 h-3 text-accent animate-pulse" />
          <span>精简模式</span>
        </div>

        <!-- 核心显示卡片 -->
        <div class="w-full bg-white border border-medical-200 shadow-2xl relative p-8 chamfer-br">
           <!-- 四角修饰 -->
          <div class="absolute top-2 left-2 w-2 h-2 border-t border-l border-medical-300"></div>
          <div class="absolute top-2 right-2 w-2 h-2 border-t border-r border-medical-300"></div>
          <div class="absolute bottom-2 left-2 w-2 h-2 border-b border-l border-medical-300"></div>
          <div class="absolute bottom-2 right-2 w-2 h-2 border-b border-r border-medical-300"></div>

          <div class="flex flex-col md:flex-row items-center gap-8">
            <!-- 封面区 -->
            <div class="relative flex-shrink-0">
               <div class="w-24 h-24 border-2 border-medical-100 flex items-center justify-center bg-medical-50 shadow-inner overflow-hidden">
                  <CoverImage :src="playerStore.nowPlaying?.music.coverUrl" class="w-full h-full object-cover" />
               </div>
               <!-- 状态环 -->
               <div v-if="!playerStore.isPaused" class="absolute -inset-2 border border-accent/20 animate-[spin_8s_linear_infinite] rounded-full border-dashed"></div>
            </div>

            <!-- 歌曲信息区 -->
            <div class="flex-1 min-w-0 flex flex-col items-center md:items-start text-center md:text-left font-sans">
               <span class="text-[10px] font-mono text-accent mb-1 tracking-widest uppercase">正在播放</span>
               <h2 class="text-2xl md:text-3xl font-black text-medical-900 tracking-tighter leading-tight mb-2 line-clamp-2">
                 {{ playerStore.nowPlaying?.music.name || '系统待机' }}
               </h2>
               <div class="flex items-center gap-2 text-sm font-bold text-medical-500">
                 <span class="w-2 h-2 bg-medical-200"></span>
                 {{ playerStore.nowPlaying?.music.artists.join(' / ') || '无内容' }}
               </div>
            </div>
          </div>
        </div>

        <!-- 音量控制 -->
        <div class="w-full max-w-[320px] bg-white/80 backdrop-blur-sm border border-medical-200 p-4 flex flex-col gap-3 shadow-sm">
           <div class="flex justify-between items-center text-[10px] font-mono text-medical-400 uppercase tracking-wider">
              <span>音量</span>
              <span class="text-medical-900 font-bold">{{ Math.round(uiStore.volume * 100) }}%</span>
           </div>
           <div class="flex items-center gap-3">
              <Volume2 class="w-4 h-4 text-medical-400 flex-shrink-0" />
              <div class="flex-1 flex items-center h-4">
                 <input
                    type="range" min="0" max="1" step="0.01"
                    v-model.number="uiStore.volume"
                    class="w-full accent-medical-900 h-1 bg-medical-100 rounded-none appearance-none cursor-pointer"
                 />
              </div>
           </div>
        </div>
        
        <!-- 后台自动精简开关 (变色优化) -->
        <label class="flex items-center gap-2 cursor-pointer group select-none">
           <div class="relative w-8 h-4 rounded-full transition-colors duration-300" 
                :class="uiStore.autoLiteMode ? 'bg-accent' : 'bg-medical-200'">
              <input type="checkbox" v-model="uiStore.autoLiteMode" class="hidden" />
              <div class="absolute left-0.5 top-0.5 w-3 h-3 bg-white rounded-full transition-transform duration-300" 
                   :style="{ transform: uiStore.autoLiteMode ? 'translateX(16px)' : 'translateX(0)' }"></div>
           </div>
           <span class="text-[10px] font-mono text-medical-400 group-hover:text-medical-600 transition-colors">
              后台播放时自动进入精简模式
           </span>
        </label>

        <!-- 退出动作 -->
        <button
            @click="uiStore.toggleLiteMode"
            class="w-full bg-medical-900 text-white font-black py-4 flex items-center justify-center gap-4 transition-all hover:bg-accent hover:tracking-[0.2em] active:scale-[0.98] group shadow-xl"
        >
          <Maximize2 class="w-5 h-5 transition-transform group-hover:scale-110" />
          <span class="text-sm tracking-widest uppercase">退出精简模式</span>
        </button>
      </div>
    </div>

    <slot v-if="!uiStore.isLiteMode" name="player"></slot>
  </div>
</template>

<style scoped>
.slide-fade-enter-active, .slide-fade-leave-active { transition: all 0.3s ease; }
.slide-fade-enter-from, .slide-fade-leave-to { transform: translateY(10px); opacity: 0; }

/* 自定义 Range 样式以匹配工业风 */
input[type=range]::-webkit-slider-thumb {
  -webkit-appearance: none;
  height: 14px;
  width: 6px;
  border-radius: 0;
  background: #111827; /* medical-900 */
  cursor: pointer;
  border: 1px solid white;
  margin-top: -5px; /* 确保滑块在轨道中央 */
  box-shadow: 0 1px 2px rgba(0,0,0,0.2);
}
input[type=range]:hover::-webkit-slider-thumb {
  background: #ff5722; /* accent */
}
input[type=range]::-webkit-slider-runnable-track {
  width: 100%;
  height: 4px;
  background: #e5e7eb;
}
</style>
