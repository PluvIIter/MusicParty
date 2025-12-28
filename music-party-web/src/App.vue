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
            class="md:hidden flex items-center justify-center w-9 h-9 bg-medical-50 border border-medical-200 text-medical-500 hover:text-medical-900 transition-colors"
            :class="{ 'bg-medical-200 text-medical-900 border-medical-300': mobileUserOpen }"
        >
          <Users class="w-5 h-5" />
        </button>

        <!-- 原有的搜索按钮 -->
        <button
            @click="handleSearchClick"
            class="flex items-center justify-center transition-colors font-bold text-sm
                       md:px-3 md:py-1 md:bg-medical-100 md:hover:bg-medical-200 md:w-auto md:h-auto md:border-0
                       w-9 h-9 bg-medical-50 border border-medical-200 text-medical-500 hover:text-medical-900 md:text-medical-800"
            :class="{'opacity-50 cursor-not-allowed': userStore.isGuest}"
        >
          <!-- 如果是游客，显示锁图标 -->
          <Lock v-if="userStore.isGuest" class="w-4 h-4 mr-1" />
          <Search v-else class="w-5 h-5 md:w-4 md:h-4" />

          <span class="hidden md:inline md:ml-2">SEARCH</span>
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

      <!-- 🟢 新增：移动端用户列表抽屉 (User) -->
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

    <!-- 🟢 新增：Toast 挂载点 -->
    <ToastNotification ref="toastInstance" />

    <!-- 强制改名弹窗 -->
    <NamePromptModal />

    <!-- Toast 挂载点 -->
    <ToastNotification ref="toastInstance" />

  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue';
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
import NamePromptModal from './components/NamePromptModal.vue'; // 🟢 [新增]
import { useUserStore } from './stores/user'; // 🟢 [新增] 确保导入了 userStore

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

let timeInterval;

const startGame = () => {
    hasStarted.value = true;
    player.connect(); // 连接 WebSocket
};

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
  if (userStore.isGuest) {
    userStore.showNameModal = true; // 游客点搜索 -> 弹改名窗
  } else {
    showSearch.value = true; // 正常用户 -> 弹搜索窗
  }
};
</script>