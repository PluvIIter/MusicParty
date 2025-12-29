<template>
  <div class="h-[100dvh] w-screen flex flex-col relative overflow-hidden bg-medical-50">
    <AuthOverlay @unlocked="isAuthPassed = true" />
    
    <!-- 启动遮罩 (需要用户点击以允许自动播放) -->
    <div v-if="isAuthPassed && !hasStarted" class="absolute inset-0 z-[100] bg-medical-50 flex flex-col items-center justify-center space-y-8">
        <div class="text-4xl font-black tracking-tighter text-medical-900">MUSIC PARTY</div>
        <div class="font-mono text-xs text-medical-400 tracking-widest">SYSTEM READY / WAITING FOR LINK</div>
        <button 
            @click="startGame" 
            class="px-12 py-4 bg-medical-900 text-white font-bold text-xl hover:bg-accent transition-colors chamfer-br relative group"
        >
            CONNECT
            <div class="absolute -inset-1 border border-medical-900 group-hover:border-accent opacity-30 scale-105 transition-all"></div>
        </button>
    </div>

    <!-- 顶部栏 -->
    <header class="h-14 bg-white border-b border-medical-200 flex justify-between items-center px-4 md:px-6 flex-shrink-0 relative z-50">
      <div class="font-black text-xl tracking-tighter text-medical-900 flex items-center gap-2">
        <div class="w-3 h-3 bg-accent"></div>
        <!-- 移动端稍微缩小标题字体 -->
        <span class="text-lg md:text-xl">MUSIC PARTY</span>
        <span class="text-medical-300 font-mono font-normal text-xs">by ThorNex</span>
      </div>
      <div class="flex items-center gap-4">
        <!-- 🟢 新增：移动端用户列表开关按钮 -->
        <button
            @click="toggleMobileUser"
            class="md:hidden relative flex items-center justify-center w-9 h-9 bg-medical-50 border border-medical-200 text-medical-500 hover:text-medical-900 transition-colors overflow-hidden group rounded-sm transform-gpu"
            :class="{ 'bg-medical-200 text-medical-900 border-medical-300': mobileUserOpen }"
        >
          <!--
            absolute inset-0: 占满容器
            flex-center: 居中
            text-5xl: 超大字体 (容器才 h-10 约 40px，5xl 是 48px，必然溢出)
            font-black: 最粗
            text-accent/20: 淡橙色
            scale-110: 进一步放大，确保填满
            translate-y-[2px]: 视觉微调，让数字重心居中
         -->
          <span
              class="absolute inset-0 flex items-center justify-center font-black text-4xl leading-none text-accent/15 pointer-events-none z-0 select-none scale-110 font-mono"
          >
            {{ userStore.onlineUsers.length > 9 ? 'N' : userStore.onlineUsers.length }}
          </span>

          <!-- 图标 (相对定位，z-10 保证在数字上层) -->
          <Users class="w-5 h-5 relative z-10" />
        </button>

        <!-- 搜索按钮 -->
        <button
            @click="handleSearchClick"
            class="relative overflow-hidden flex items-center justify-center transition-all duration-300 font-bold text-sm
                   md:px-4 md:py-1.5
                   w-9 h-9 md:w-auto md:h-auto border group"
            :class="[
                isGuestHighlight
                    ? 'bg-accent border-accent text-white shadow-md shadow-accent/20'
                    : 'bg-medical-50 border-medical-200 text-medical-500 hover:text-medical-900 md:bg-medical-100 md:text-medical-800 md:hover:bg-medical-200 md:border-transparent'
            ]"
        >
          <!-- 动态扫描线背景 (仅在高亮模式下显示) -->
          <div v-if="isGuestHighlight"
               class="absolute inset-0 bg-[url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAADCAYAAABS3WWCAAAAE0lEQVQYV2NkYGD4zwABjFAQAwBATgMJy2B8NAAAAABJRU5ErkJggg==')] opacity-20 pointer-events-none animate-scan">
          </div>

          <!-- 图标 -->
          <Search class="w-5 h-5 md:w-4 md:h-4 relative z-10" />

          <!-- 文字 -->
          <span class="hidden md:inline md:ml-2 relative z-10">
              SEARCH
          </span>
        </button>
        <div class="font-mono text-xs text-medical-500 hidden md:block">{{ currentTime }}</div>
      </div>
    </header>

    <!-- 主体布局 -->
    <div v-if="isAuthPassed" class="flex-1 flex overflow-hidden relative">
      <!-- 左侧边栏 (PC only) -->
      <aside class="w-64 bg-medical-50 border-r border-medical-200 hidden md:block overflow-y-auto">
        <UserList />
      </aside>

      <!-- 中间内容 -->
      <main class="flex-1 bg-medical-100/30 relative flex flex-col overflow-hidden z-10">
        <CenterConsole />
      </main>

      <!-- 右侧边栏 (PC only) -->
      <aside class="w-80 bg-white border-l border-medical-200 hidden md:block overflow-hidden">
        <QueueList />
      </aside>

      <!-- 移动端: 浮动按钮打开队列 (修改点击事件) -->
      <div class="md:hidden absolute top-4 right-4 z-40">
        <button @click="toggleMobileQueue" class="p-2 bg-white shadow border border-medical-200">
          <ListMusic class="w-5 h-5"/>
        </button>
      </div>

      <!-- 移动端队列抽屉 (Queue) -->
      <div v-if="mobileQueueOpen" class="md:hidden absolute inset-0 bg-white z-30 pt-4 overflow-y-auto">
        <!-- 加个关闭按钮或者头部 -->
        <div class="px-4 pb-2 border-b border-medical-100 mb-2 flex justify-between items-center text-xs font-mono text-medical-400">
          <span>QUEUE PANEL</span>
          <button @click="mobileQueueOpen = false">dX</button>
        </div>
        <QueueList />
      </div>

      <!-- 移动端用户列表抽屉 (User) -->
      <div v-if="mobileUserOpen" class="md:hidden absolute inset-0 bg-medical-50 z-30 pt-4 overflow-y-auto">
        <div class="px-4 pb-2 border-b border-medical-200 mb-2 flex justify-between items-center text-xs font-mono text-medical-400">
          <span>BVOPERATIVES PANEL</span>
          <button @click="mobileUserOpen = false">dX</button>
        </div>
        <!-- 复用 UserList 组件，它包含了改名输入框 -->
        <UserList />
      </div>
    </div>

    <!-- 底部播放器 -->
    <PlayerControl v-if="hasStarted" class="flex-shrink-0" />

    <!-- 弹窗 -->
    <SearchModal :isOpen="showSearch" @close="showSearch = false" />

    <!-- Toast 挂载点 -->
    <ToastNotification ref="toastInstance" />

    <!-- 强制改名弹窗 -->
    <NamePromptModal />

    <!-- 挂载聊天组件 -->
    <ChatOverlay v-if="hasStarted" />

    <!-- Toast 挂载点 -->
    <ToastNotification ref="toastInstance" />
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed } from 'vue';
import { usePlayerStore } from './stores/player';
import { Search, ListMusic, Users, Lock } from 'lucide-vue-next';
import dayjs from 'dayjs';
import UserList from './components/UserList.vue';
import QueueList from './components/QueueList.vue';
import PlayerControl from './components/PlayerControl.vue';
import SearchModal from './components/SearchModal.vue';
import CenterConsole from './components/CenterConsole.vue';
import ToastNotification from './components/ToastNotification.vue'; // 导入组件
import { useToast } from './composables/useToast'; // 导入钩子
import AuthOverlay from './components/AuthOverlay.vue';
import NamePromptModal from './components/NamePromptModal.vue';
import { useUserStore } from './stores/user';
import ChatOverlay from './components/ChatOverlay.vue';

const player = usePlayerStore();
const hasStarted = ref(false);
const showSearch = ref(false);
const mobileQueueOpen = ref(false);
const currentTime = ref('');
const toastInstance = ref(null);
const isAuthPassed = ref(false);
const { register } = useToast();
const mobileUserOpen = ref(false);
const userStore = useUserStore();
const hasInteracted = ref(false);

let timeInterval;

const startGame = () => {
    hasStarted.value = true;
    player.connect(); // 连接 WebSocket
};

// 是否处于“新手引导高亮”状态
// 条件：是访客 AND 还没点过按钮
const isGuestHighlight = computed(() => {
  return userStore.isGuest && !hasInteracted.value;
});

const toggleMobileQueue = () => {
  mobileQueueOpen.value = !mobileQueueOpen.value;
  if (mobileQueueOpen.value) mobileUserOpen.value = false; // 关闭另一个
};

const toggleMobileUser = () => {
  mobileUserOpen.value = !mobileUserOpen.value;
  if (mobileUserOpen.value) mobileQueueOpen.value = false; // 关闭另一个
};

onMounted(() => {
    if (toastInstance.value) {
      register(toastInstance.value);
    }
    timeInterval = setInterval(() => {
        currentTime.value = dayjs().format('HH:mm:ss');
    }, 1000);
});

onUnmounted(() => clearInterval(timeInterval));

const handleSearchClick = () => {
  hasInteracted.value = true;

  if (userStore.isGuest) {
    // 注册回调：改名成功后，把 showSearch 设为 true
    userStore.setPostNameAction(() => {
      showSearch.value = true;
    });

    userStore.showNameModal = true;
  } else {
    showSearch.value = true;
  }
};
</script>