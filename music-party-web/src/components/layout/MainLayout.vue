<!-- src/components/layout/MainLayout.vue -->
<template>
  <div class="h-[100dvh] w-screen flex flex-col relative overflow-hidden bg-medical-50">
    <!-- 1. 顶部栏 Header -->
    <header class="h-14 bg-white border-b border-medical-200 flex justify-between items-center px-4 md:px-6 flex-shrink-0 relative z-50">
      <div class="font-black text-xl tracking-tighter text-medical-900 flex items-center gap-2">
        <div class="w-3 h-3 bg-accent"></div>
        <span class="text-lg md:text-xl">MUSIC PARTY</span>
        <span class="text-medical-300 font-mono font-normal text-xs">by ThorNex</span>
      </div>

      <div class="flex items-center gap-4">
        <!-- 移动端：显示人数按钮 -->
        <button @click="toggleMobileUser" class="md:hidden w-9 h-9 border border-medical-200 flex items-center justify-center rounded-sm text-medical-500" :class="mobileUserOpen ? 'bg-medical-200 text-medical-900' : 'bg-medical-50'">
          <Users class="w-5 h-5" />
        </button>

        <!-- 搜索按钮 -->
        <button @click="handleSearchClick" class="flex items-center justify-center w-9 h-9 md:w-auto md:h-auto md:px-4 md:py-1.5 border border-medical-200 bg-medical-50 hover:bg-medical-100 font-bold text-sm text-medical-600 transition-all rounded-sm gap-2">
          <Search class="w-4 h-4" />
          <span class="hidden md:inline">SEARCH</span>
        </button>
      </div>
    </header>

    <!-- 2. 主体内容区 -->
    <div class="flex-1 flex overflow-hidden relative">
      <!-- 左侧：用户列表 (PC) -->
      <aside class="w-64 bg-medical-50 border-r border-medical-200 hidden md:block overflow-y-auto">
        <UserList />
      </aside>

      <!-- 中间：控制台 (Slot 插槽，由 App.vue 传入 CenterConsole) -->
      <main class="flex-1 bg-medical-100/30 relative flex flex-col overflow-hidden z-10">
        <slot></slot>
      </main>

      <!-- 右侧：队列 (PC) -->
      <aside class="w-80 bg-white border-l border-medical-200 hidden md:block overflow-hidden">
        <QueueList />
      </aside>

      <!-- 移动端浮层：队列 -->
      <div class="md:hidden absolute top-4 right-4 z-40">
        <button @click="toggleMobileQueue" class="p-2 bg-white shadow border border-medical-200 rounded-sm">
          <ListMusic class="w-5 h-5 text-medical-600"/>
        </button>
      </div>

      <!-- 移动端抽屉 -->
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

    <!-- 3. 底部播放器 (Slot 插槽) -->
    <slot name="player"></slot>
  </div>
</template>

<script setup>
import { ref } from 'vue';
import { Search, Users, ListMusic, X } from 'lucide-vue-next';
import UserList from '../UserList.vue'; // 确保路径正确，可能需要调整 ../
import QueueList from '../QueueList.vue';

const emit = defineEmits(['search']);

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

<style scoped>
.slide-fade-enter-active, .slide-fade-leave-active { transition: all 0.3s ease; }
.slide-fade-enter-from, .slide-fade-leave-to { transform: translateY(10px); opacity: 0; }
</style>