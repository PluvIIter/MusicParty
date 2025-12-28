<!-- File Path: music-party-web\src\components\ChatOverlay.vue -->

<template>
  <!--
    外层容器
    pointer-events-none: 确保透明区域不挡住下面内容的点击
  -->
  <div
      :style="{ left: x + 'px', top: y + 'px' }"
      class="fixed z-[100] flex flex-col items-center touch-none pointer-events-none"
  >

    <!--
      聊天窗口
      pointer-events-auto: 恢复内部点击
      移动端适配优化：
        w-[calc(100vw-32px)]: 宽度占满屏幕减去两边边距
        max-w-[320px]: 最大宽度限制
        max-h-[50vh]: 高度限制
    -->
    <Transition
        enter-active-class="transition-all duration-300 ease-out"
        enter-from-class="opacity-0 scale-95"
        enter-to-class="opacity-100 scale-100"
        leave-active-class="transition-all duration-200 ease-in"
        leave-from-class="opacity-100 scale-100"
        leave-to-class="opacity-0 scale-95"
    >
      <div
          v-if="chatStore.isOpen"
          class="absolute pointer-events-auto bg-white border border-medical-200 shadow-2xl flex flex-col chamfer-br overflow-hidden w-[80vw] max-w-[300px] h-[45vh] md:h-[450px]"
          :class="windowPositionClasses"
          @mousedown.stop
          @touchstart.stop
      >
        <!-- Header -->
        <div class="h-9 bg-medical-50 border-b border-medical-200 flex items-center justify-between px-3 flex-shrink-0">
          <div class="font-mono text-xs font-bold text-medical-500 flex items-center gap-2">
            <MessageSquare class="w-3 h-3"/> CHAT
          </div>
          <button @click="chatStore.toggleChat" class="text-medical-400 hover:text-medical-900 p-1">
            <X class="w-4 h-4"/>
          </button>
        </div>

        <!-- Messages List -->
      <div
          ref="msgListRef"
          class="flex-1 overflow-y-auto p-3 space-y-3 bg-medical-50/30 chat-scroll"
      >
          <div v-if="chatStore.messages.length === 0" class="text-center py-8 text-[10px] text-medical-300 font-mono">
            > CHANNEL READY.
          </div>

          <div
              v-for="msg in chatStore.messages"
              :key="msg.id"
              class="flex flex-col text-sm group"
              :class="isSelf(msg) ? 'items-end' : 'items-start'"
          >
            <!-- Name -->
            <div class="flex items-center gap-2 text-[10px] text-medical-400 mb-0.5 font-mono">
              <span v-if="!isSelf(msg)">{{ userStore.resolveName(msg.userId, msg.userName) }}</span>
            </div>

            <!-- Bubble -->
            <div
                class="max-w-[90%] px-3 py-1.5 text-xs break-words relative shadow-sm leading-relaxed"
                :class="isSelf(msg)
                ? 'bg-medical-900 text-white rounded-l-md rounded-tr-md'
                : 'bg-white border border-medical-200 text-medical-800 rounded-r-md rounded-tl-md'"
            >
              {{ msg.content }}
            </div>
          </div>
        </div>

        <!-- Input Area -->
        <div class="p-2 bg-white border-t border-medical-200 flex gap-2">
          <input
              v-model="inputContent"
              @keyup.enter="send"
              @mousedown.stop
              @touchstart.stop
              placeholder="TYPE..."
              class="flex-1 bg-medical-50 border border-medical-200 px-2 py-1.5 text-xs outline-none focus:border-accent font-mono transition-colors rounded-sm text-medical-900"
          />
          <button
              @click="send"
              class="bg-accent hover:bg-accent-hover text-white px-3 py-1.5 transition-colors rounded-sm flex items-center justify-center shadow-sm shadow-accent/20"
          >
            <Send class="w-4 h-4" />
          </button>
        </div>
      </div>
    </Transition>

    <!--
      悬浮开关按钮 (拖拽手柄)
      方形样式：w-10 h-10 rounded-sm
      pointer-events-auto: 恢复点击
      touch-action: none (useDraggable 会处理)
    -->
    <div
        ref="dragHandle"
        @pointerdown="handlePointerDown"
        @click="handleClick"
        class="pointer-events-auto w-10 h-10 border shadow-lg flex items-center justify-center transition-all cursor-move select-none rounded-sm relative"
        :class="chatStore.unreadCount > 0
            ? 'bg-accent border-accent text-white shadow-accent/30 animate-pulse-slow'
            : 'bg-white border-medical-200 text-medical-500 hover:text-medical-900 hover:border-medical-300'"
    >
      <!-- 如果有未读消息，显示数字；否则显示图标 -->
      <span v-if="chatStore.unreadCount > 0" class="font-bold font-mono text-sm">
         {{ chatStore.unreadCount > 9 ? '9+' : chatStore.unreadCount }}
      </span>
      <MessageSquare v-else class="w-5 h-5"/>

    </div>

  </div>
</template>

<script setup>
import { ref, watch, nextTick, computed, onMounted } from 'vue';
import { useChatStore } from '../stores/chat';
import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import { useDraggable, useWindowSize, useEventListener, clamp } from '@vueuse/core';
import { MessageSquare, X, Send } from 'lucide-vue-next';

const chatStore = useChatStore();
const playerStore = usePlayerStore();
const userStore = useUserStore();
const { width: windowWidth, height: windowHeight } = useWindowSize();

const inputContent = ref('');
const msgListRef = ref(null);
const dragHandle = ref(null);

const BUTTON_SIZE = 40; // 按钮大小
const MARGIN = 10;      // 屏幕边缘留白

// 1. 初始化拖拽
const { x, y } = useDraggable(dragHandle, {
  initialValue: { x: window.innerWidth - 60, y: window.innerHeight - 150 },
  preventDefault: true,
  onMove: (position) => {
    // 限制 X 轴：0 + Margin ~ 屏幕宽 - 按钮宽 - Margin
    position.x = clamp(position.x, MARGIN, window.innerWidth - BUTTON_SIZE - MARGIN);
    // 限制 Y 轴：0 + Margin ~ 屏幕高 - 按钮高 - Margin
    position.y = clamp(position.y, MARGIN, window.innerHeight - BUTTON_SIZE - MARGIN);
  }
});

// 2. 防误触点击
let startDragPos = { x: 0, y: 0 };
const handlePointerDown = (e) => {
  startDragPos = { x: e.clientX, y: e.clientY };
};
const handleClick = (e) => {
  const dx = Math.abs(e.clientX - startDragPos.x);
  const dy = Math.abs(e.clientY - startDragPos.y);
  if (dx > 5 || dy > 5) return; // 位移过大视为拖拽

  if (userStore.isGuest) {
    userStore.showNameModal = true;
    return;
  }
  chatStore.toggleChat();
};

// 3. 智能弹出方向
const isRightSide = computed(() => x.value > windowWidth.value / 2);
const isBottomSide = computed(() => y.value > windowHeight.value / 2);

const windowPositionClasses = computed(() => {
  const classes = [];
  // 间距 12px (space between button and window)
  if (isRightSide.value) classes.push('right-12'); else classes.push('left-12');
  if (isBottomSide.value) classes.push('bottom-0'); else classes.push('top-0');
  return classes.join(' ');
});

// 窗口 Resize 时重置位置
const resetPosition = () => {
  // 强制把 x, y 拉回到可视范围内
  x.value = clamp(x.value, MARGIN, windowWidth.value - BUTTON_SIZE - MARGIN);
  y.value = clamp(y.value, MARGIN, windowHeight.value - BUTTON_SIZE - MARGIN);
};
useEventListener(window, 'resize', resetPosition);

const isSelf = (msg) => msg.userId === userStore.userToken;

const send = () => {
  const text = inputContent.value.trim();
  if (!text) return;
  playerStore.sendChatMessage(text);
  inputContent.value = '';
};

const scrollToBottom = async () => {
  await nextTick();
  if (msgListRef.value) {
    msgListRef.value.scrollTop = msgListRef.value.scrollHeight;
  }
};

watch(() => chatStore.messages.length, scrollToBottom);
watch(() => chatStore.isOpen, (val) => {
  if (val) {
    chatStore.unreadCount = 0; // 打开即已读
    scrollToBottom();
  }
});

onMounted(resetPosition);
</script>

<style scoped>
/* 🟢 自定义滚动条样式 */
.chat-scroll::-webkit-scrollbar {
  width: 4px; /* 更细 */
}
.chat-scroll::-webkit-scrollbar-track {
  background: transparent;
}
.chat-scroll::-webkit-scrollbar-thumb {
  @apply bg-accent/50 rounded; /* 主题色半透明 */
}
.chat-scroll::-webkit-scrollbar-thumb:hover {
  @apply bg-accent;
}

/* 呼吸动画 */
.animate-pulse-slow {
  animation: pulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: .85; }
}
</style>