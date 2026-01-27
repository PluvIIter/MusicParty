<template>
  <!--
    外层容器
    pointer-events-none: 确保透明区域不挡住下面内容的点击
    z-[100]: 确保在大多数内容之上
  -->
  <div
      :style="{ left: x + 'px', top: y + 'px' }"
      class="fixed z-[100] flex flex-col items-center touch-none pointer-events-none"
  >

    <!--
      聊天窗口
      pointer-events-auto: HB恢复内部点击
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
          class="absolute pointer-events-auto bg-white border border-medical-200 shadow-2xl flex flex-col chamfer-br overflow-hidden w-[85vw] max-w-[340px] h-[50vh] md:h-[480px]"
          :class="windowPositionClasses"
          @mousedown.stop
          @touchstart.stop
      >
        <!--
           1. Header (支持拖拽)
           cursor-move: 提示可拖拽
        -->
        <div
            ref="windowHeaderRef"
            @pointerdown="startHeaderDrag"
            class="h-10 bg-medical-50 border-b border-medical-200 flex items-center justify-between px-3 flex-shrink-0 cursor-move select-none"
        >
          <div class="font-mono text-xs font-bold text-medical-500 flex items-center gap-2">
            <MessageSquare class="w-3 h-3"/> COMM_Ui
          </div>
          <button @click="chatStore.toggleChat" class="text-medical-400 hover:text-medical-900 p-1 cursor-pointer">
            <X class="w-4 h-4"/>
          </button>
        </div>

        <!-- 2. Tabs 切换栏 -->
        <div class="flex border-b border-medical-200 bg-medical-50/50">
          <button
              v-for="tab in ['CHAT', 'SYSTEM']"
              :key="tab"
              @click="activeTab = tab"
              class="flex-1 py-2 text-[10px] font-bold font-mono transition-colors relative"
              :class="activeTab === tab ? 'text-medical-900 bg-white' : 'text-medical-400 hover:text-medical-600 hover:bg-medical-100'"
          >
            {{ tab }}
            <!-- 激活指示条 -->
            <div v-if="activeTab === tab" class="absolute top-0 left-0 w-full h-0.5 bg-accent"></div>
          </button>
        </div>

        <!-- 3. Messages List -->
        <div
            ref="msgListRef"
            @scroll="handleScroll"
            class="flex-1 overflow-y-auto p-3 space-y-4 bg-medical-50/30 chat-scroll"
        >
          <!-- Loading More Indicator -->
          <div v-if="chatStore.isLoadingMore" class="flex justify-center py-2">
            <Loader2 class="w-4 h-4 animate-spin text-accent/50" />
          </div>

          <div v-if="processedMessages.length === 0" class="text-center py-8 text-[10px] text-medical-300 font-mono">
            > NO RECORDS IN {{ activeTab }}
          </div>

          <div
              v-for="(item, index) in processedMessages"
              :key="item.msg.id"
          >
            <!-- 时间戳 (如果与上一条间隔超过3分钟则显示) -->
            <div v-if="item.showTime" class="flex justify-center mb-3">
              <span class="text-[9px] font-mono text-medical-300 bg-medical-100/50 px-2 py-0.5 rounded-sm">
                {{ formatTime(item.msg.timestamp) }}
              </span>
            </div>

            <!-- 消息体 -->
            <!-- 情况A: 聊天消息 (CHAT) -->
            <div
                v-if="item.msg.type === 'CHAT'"
                class="flex flex-col text-sm group"
                :class="isSelf(item.msg) ? 'items-end' : 'items-start'"
            >
              <div class="flex items-center gap-2 text-[10px] text-medical-400 mb-0.5 font-mono">
                <span v-if="!isSelf(item.msg)">{{ userStore.resolveName(item.msg.userId, item.msg.userName) }}</span>
              </div>
              <div
                  class="max-w-[90%] px-3 py-1.5 text-xs break-words relative shadow-sm leading-relaxed"
                  :class="isSelf(item.msg)
                    ? 'bg-medical-900 text-white rounded-l-md rounded-tr-md'
                    : 'bg-white border border-medical-200 text-medical-800 rounded-r-md rounded-tl-md'"
              >
                {{ item.msg.content }}
              </div>
            </div>

            <!-- 情况B: 系统日志 (SYSTEM) -->
            <div
                v-else-if="item.msg.type === 'SYSTEM'"
                class="flex items-start gap-2 text-xs text-medical-500/80 px-2 select-none opacity-80"
            >
              <Terminal class="w-3 h-3 mt-0.5 flex-shrink-0 opacity-50"/>
              <span class="font-mono text-[10px] leading-relaxed break-all">
                {{ item.msg.content }}
              </span>
            </div>

            <!-- 情况C: 点赞 (LIKE) -->
            <div
                v-else-if="item.msg.type === 'LIKE'"
                class="flex justify-center my-1"
            >
              <div class="bg-accent/5 border border-accent/20 text-accent px-3 py-1 rounded-full text-[10px] font-bold flex items-center gap-1 shadow-sm">
                <Heart class="w-3 h-3 fill-accent"/>
                <span>{{ userStore.resolveName(item.msg.userId, item.msg.userName) }} Liked!</span>
              </div>
            </div>

          </div>
        </div>

        <!-- 4. Input Area (仅在 Chat Tab 显示) -->
        <div v-if="activeTab === 'CHAT'" class="p-2 bg-white border-t border-medical-200 flex gap-2 flex-shrink-0">
          <input
              v-model="inputContent"
              @keyup.enter="send"
              @mousedown.stop
              @touchstart.stop
              placeholder="TYPE MESSAGE..."
              class="flex-1 bg-medical-50 border border-medical-200 px-2 py-1.5 text-xs outline-none focus:border-accent font-mono transition-colors rounded-sm text-medical-900 placeholder-medical-300"
          />
          <button
              @click="send"
              class="bg-accent hover:bg-accent-hover text-white px-3 py-1.5 transition-colors rounded-sm flex items-center justify-center shadow-sm shadow-accent/20"
          >
            <Send class="w-4 h-4" />
          </button>
        </div>

        <!-- System Tab 底部占位 -->
        <div v-else class="h-6 bg-medical-50 border-t border-medical-200 flex items-center justify-center">
          <span class="text-[9px] font-mono text-medical-300">SYSTEM LOG READ-ONLY</span>
        </div>
      </div>
    </Transition>

    <!--
      悬浮开关按钮 (拖拽手柄)
    -->
    <div
        ref="dragHandle"
        @pointerdown="handlePointerDown"
        @click="handleClick"
        class="pointer-events-auto w-10 h-10 border flex items-center justify-center transition-all cursor-move select-none rounded-sm relative overflow-hidden"
        :class="chatStore.unreadCount > 0
            ? 'bg-accent border-accent text-white shadow-[0_0_15px_rgba(249,115,22,0.6)] scale-110'
            : 'bg-white border-medical-200 text-medical-500 shadow-lg hover:text-medical-900 hover:border-medical-300'"
    >
      <div v-if="chatStore.unreadCount > 0"
           class="absolute inset-0 bg-[url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAADCAYAAABS3WWCAAAAE0lEQVQYV2NkYGD4zwABjFAQAwBATgMJy2B8NAAAAABJRU5ErkJggg==')] opacity-30 pointer-events-none animate-scan z-0">
      </div>

      <span v-if="chatStore.unreadCount > 0" class="font-bold font-mono text-sm relative z-10 animate-pulse">
         {{ chatStore.unreadCount > 99 ? '99+' : chatStore.unreadCount }}
      </span>

      <MessageSquare v-else class="w-5 h-5 relative z-10"/>
    </div>

  </div>
</template>

<script setup>
import { ref, watch, nextTick, computed, onMounted } from 'vue';
import { useChatStore } from '../stores/chat';
import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import { useDraggable, useWindowSize, useEventListener, clamp } from '@vueuse/core';
import { MessageSquare, X, Send, Terminal, Heart, Loader2 } from 'lucide-vue-next';
import dayjs from 'dayjs';

const chatStore = useChatStore();
const playerStore = usePlayerStore();
const userStore = useUserStore();
const { width: windowWidth, height: windowHeight } = useWindowSize();

const inputContent = ref('');
const msgListRef = ref(null);
const dragHandle = ref(null);
const windowHeaderRef = ref(null);

const activeTab = ref('CHAT'); // 'CHAT' | 'SYSTEM'

const BUTTON_SIZE = 40;
const MARGIN = 10;

// === 1. 拖拽逻辑 (主控制器) ===
// useDraggable 绑定在悬浮球上，它是坐标 (x, y) 的事实来源
const { x, y } = useDraggable(dragHandle, {
  initialValue: { x: window.innerWidth - 60, y: window.innerHeight - 150 },
  preventDefault: true,
  onMove: (position) => {
    position.x = clamp(position.x, MARGIN, window.innerWidth - BUTTON_SIZE - MARGIN);
    position.y = clamp(position.y, MARGIN, window.innerHeight - BUTTON_SIZE - MARGIN);
  }
});

// === 2. 标题栏拖拽同步逻辑 ===
const startHeaderDrag = (e) => {
  // 记录按下时的鼠标位置和当前的 x, y
  const startMouseX = e.clientX;
  const startMouseY = e.clientY;
  const startX = x.value;
  const startY = y.value;

  const onMouseMove = (me) => {
    // 计算位移并直接更新 x, y Ref
    let newX = startX + (me.clientX - startMouseX);
    let newY = startY + (me.clientY - startMouseY);

    // 同样应用边界限制
    newX = clamp(newX, MARGIN, window.innerWidth - BUTTON_SIZE - MARGIN);
    newY = clamp(newY, MARGIN, window.innerHeight - BUTTON_SIZE - MARGIN);

    x.value = newX;
    y.value = newY;
  };

  const onMouseUp = () => {
    window.removeEventListener('pointermove', onMouseMove);
    window.removeEventListener('pointerup', onMouseUp);
  };

  window.addEventListener('pointermove', onMouseMove);
  window.addEventListener('pointerup', onMouseUp);
};

// === 3. 点击与防误触 ===
let startDragPos = { x: 0, y: 0 };
const handlePointerDown = (e) => {
  startDragPos = { x: e.clientX, y: e.clientY };
};
const handleClick = (e) => {
  const dx = Math.abs(e.clientX - startDragPos.x);
  const dy = Math.abs(e.clientY - startDragPos.y);
  if (dx > 5 || dy > 5) return; // 位移过大视为拖拽

  if (userStore.isGuest) {
    userStore.setPostNameAction(() => {
      if(!chatStore.isOpen) chatStore.toggleChat();
    });
    userStore.showNameModal = true;
    return;
  }
  chatStore.toggleChat();
};

// === 4. 窗口智能定位 ===
const isRightSide = computed(() => x.value > windowWidth.value / 2);
const isBottomSide = computed(() => y.value > windowHeight.value / 2);

const windowPositionClasses = computed(() => {
  const classes = [];
  if (isRightSide.value) classes.push('right-12'); else classes.push('left-12');
  if (isBottomSide.value) classes.push('bottom-0'); else classes.push('top-0');
  return classes.join(' ');
});

const resetPosition = () => {
  x.value = clamp(x.value, MARGIN, windowWidth.value - BUTTON_SIZE - MARGIN);
  y.value = clamp(y.value, MARGIN, windowHeight.value - BUTTON_SIZE - MARGIN);
};
useEventListener(window, 'resize', resetPosition);

// === 5. 消息处理与展示逻辑 ===
const isSelf = (msg) => msg.userId === userStore.userToken;

const formatTime = (ts) => dayjs(ts).format('HH:mm');

// 核心：过滤并计算时间戳显示
const processedMessages = computed(() => {
  // 1. 根据 Tab 过滤
  const filtered = chatStore.messages.filter(msg => {
    // CHAT Tab: 聊天 + 点赞
    if (activeTab.value === 'CHAT') {
      return msg.type === 'CHAT' || msg.type === 'LIKE';
    }
    // SYSTEM Tab: 系统 + 点赞
    if (activeTab.value === 'SYSTEM') {
      return msg.type === 'SYSTEM' || msg.type === 'LIKE';
    }
    return false;
  });

  // 2. 计算是否显示时间
  const result = [];
  let lastTime = 0;
  const TIME_THRESHOLD = 3 * 60 * 1000; // 3分钟

  for (const msg of filtered) {
    let showTime = false;
    if (msg.timestamp - lastTime > TIME_THRESHOLD) {
      showTime = true;
      lastTime = msg.timestamp;
    }
    result.push({ msg, showTime });
  }

  return result;
});

// === 6. 滚动与分页逻辑 ===
const scrollToBottom = async (force = false) => {
  await nextTick();
  if (msgListRef.value) {
    const el = msgListRef.value;
    // 只有当用户已经在底部，或者强制滚动时，才自动滚到底
    // 允许 50px 的误差
    const isAtBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 50;
    if (isAtBottom || force) {
      el.scrollTop = el.scrollHeight;
    }
  }
};

// 监听滚动加载更多
const handleScroll = (e) => {
  const el = e.target;
  // 触顶 && 还有更多 && 没在加载
  if (el.scrollTop < 20 && chatStore.hasMore && !chatStore.isLoadingMore) {
    // 记录加载前的高度
    const oldHeight = el.scrollHeight;

    // 触发加载
    chatStore.loadMoreHistory();

    // 加载完成后恢复位置
    // 我们需要监听 messages 长度变化来执行恢复
    const unwatch = watch(() => chatStore.messages.length, async () => {
      await nextTick();
      const newHeight = el.scrollHeight;
      el.scrollTop = newHeight - oldHeight; // 保持视口停留在原来的消息处
      unwatch(); // 仅执行一次
    });
  }
};

// === 7. 交互动作 ===
const send = () => {
  const text = inputContent.value.trim();
  if (!text) return;
  playerStore.sendChatMessage(text);
  inputContent.value = '';
  // 发送后强制滚到底部
  setTimeout(() => scrollToBottom(true), 100);
};

// 监听：打开窗口或切换 Tab 时滚到底部
watch([() => chatStore.isOpen, activeTab], async ([isOpen]) => {
  if (isOpen) {
    chatStore.unreadCount = 0; // 只要打开就清空未读
    await scrollToBottom(true);
  }
});

// 监听：收到新消息时 (且在当前Tab)，尝试滚到底部
watch(() => processedMessages.value.length, (newLen, oldLen) => {
  // 如果是增量追加(正常聊天)，且在底部，则自动滚
  // 如果是历史加载(头部追加)，则不由这里处理(由handleScroll处理)
  if (newLen > oldLen) {
    scrollToBottom(false);
  }
});

onMounted(() => {
  resetPosition();
});
</script>

<style scoped>
.chat-scroll::-webkit-scrollbar {
  width: 4px;
}
.chat-scroll::-webkit-scrollbar-track {
  background: transparent;
}
.chat-scroll::-webkit-scrollbar-thumb {
  @apply bg-accent/20 rounded;
}
.chat-scroll::-webkit-scrollbar-thumb:hover {
  @apply bg-accent/50;
}
</style>