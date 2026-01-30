<template>
  <div v-if="isActive" class="fixed inset-0 z-[9999] pointer-events-auto">
    <!-- 背景遮罩 (半透明黑) -->
    <div class="absolute inset-0 bg-black/50 transition-opacity duration-500"></div>

    <!-- 聚光灯效果 (可选，或者直接显示高亮框) -->
    <!-- 我们使用一个绝对定位的高亮框来框住目标元素 -->
    <div
        v-if="targetRect"
        class="absolute border-2 border-accent shadow-[0_0_20px_rgba(var(--color-accent),0.5)] transition-all duration-300 ease-out pointer-events-none"
        :style="{
          top: targetRect.top - 4 + 'px',
          left: targetRect.left - 4 + 'px',
          width: targetRect.width + 8 + 'px',
          height: targetRect.height + 8 + 'px',
          borderRadius: '4px'
        }"
    >
      <!-- 装饰角标 -->
      <div class="absolute -top-1 -left-1 w-3 h-3 border-t-2 border-l-2 border-accent bg-transparent"></div>
      <div class="absolute -top-1 -right-1 w-3 h-3 border-t-2 border-r-2 border-accent bg-transparent"></div>
      <div class="absolute -bottom-1 -left-1 w-3 h-3 border-b-2 border-l-2 border-accent bg-transparent"></div>
      <div class="absolute -bottom-1 -right-1 w-3 h-3 border-b-2 border-r-2 border-accent bg-transparent"></div>
    </div>

    <!-- 提示框 -->
    <div
        v-if="currentStep"
        class="absolute bg-white border border-medical-200 p-4 shadow-xl max-w-xs md:max-w-sm transition-all duration-300 chamfer-br flex flex-col gap-3"
        :style="tooltipStyle"
    >
      <div class="flex items-center justify-between border-b border-medical-100 pb-2">
        <span class="text-xs font-mono font-bold text-accent">TUTORIAL_SYSTEM // {{ currentStepIndex + 1 }}/{{ steps.length }}</span>
        <button @click="skipTutorial" class="text-xs text-medical-400 hover:text-medical-900 font-mono">[SKIP]</button>
      </div>
      
      <div class="text-sm font-bold text-medical-900 leading-relaxed">
        {{ currentStep.content }}
      </div>

      <div class="flex justify-end pt-2">
        <button
            @click="nextStep"
            class="px-4 py-1.5 bg-medical-900 text-white text-xs font-bold hover:bg-accent transition-colors chamfer-br"
        >
          {{ currentStepIndex === steps.length - 1 ? 'FINISH' : 'NEXT >' }}
        </button>
      </div>

      <!-- 连接线 (简单的视觉装饰) -->
      <div 
        class="absolute w-4 h-4 bg-white border-l border-b border-medical-200 transform rotate-45"
        :class="arrowClass"
      ></div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick, watch } from 'vue';
import { useWindowSize } from '@vueuse/core';

const isActive = ref(false);
const currentStepIndex = ref(0);
const targetRect = ref(null);

const { width, height } = useWindowSize();

const STORAGE_KEY = 'mp_tutorial_done_v1';

const steps = [
  {
    targetId: 'tutorial-rename',
    content: '点击这里可以修改你的昵称，输入后按回车确认。'
  },
  {
    targetId: 'tutorial-search',
    content: '点击搜索按钮寻找歌曲。在此处也可以通过搜索用户名来查看平台账号歌单。'
  },
  {
    targetId: 'tutorial-like',
    content: '点击中间的封面可以为当前歌曲点赞。'
  },
  {
    targetId: 'tutorial-queue',
    content: '这里是播放队列。悬停在歌曲上可以进行置顶或删除操作。'
  },
  {
    targetId: 'tutorial-pause',
    content: '注意：暂停/播放是全局生效的，会影响所有在线听众，请谨慎操作。'
  },
  {
    targetId: 'tutorial-random',
    content: '随机播放模式采用“公平随机”算法，确保每个人点的歌都有均等的机会被播放。'
  },
  {
    targetId: 'tutorial-download',
    content: '听到喜欢的歌？点击这里可以直接下载当前播放的音频文件。'
  },
  {
    targetId: 'tutorial-chat',
    content: '点击浮动按钮打开聊天窗口，可以和其他人聊天或查看记录。按钮可以拖动。'
  },
  {
    targetId: 'tutorial-source',
    content: '点击底部的小封面，可以跳转到歌曲的源网页。'
  }
];

const currentStep = computed(() => steps[currentStepIndex.value]);

const tooltipStyle = ref({});
const arrowClass = ref('');

const updatePosition = async () => {
  if (!isActive.value) return;
  await nextTick();
  
  const step = currentStep.value;
  const el = document.getElementById(step.targetId);
  
  if (!el) {
    // 如果找不到元素（可能在移动端隐藏了），自动跳过该步骤
    console.warn(`Tutorial target ${step.targetId} not found, skipping.`);
    if (currentStepIndex.value < steps.length - 1) {
      currentStepIndex.value++;
      return; // Watcher will trigger updatePosition again
    } else {
      finishTutorial();
      return;
    }
  }

  const rect = el.getBoundingClientRect();
  targetRect.value = rect;

  // 计算 Tooltip 位置
  // 简单策略：优先放在下方，如果不够放则上方，再不够放则左/右
  const tooltipWidth = 300; // estimated
  const tooltipHeight = 150; // estimated
  const margin = 16;

  let top, left;
  let arrowPos = '';

  // 尝试放在下方
  if (rect.bottom + tooltipHeight + margin < window.innerHeight) {
    top = rect.bottom + margin;
    left = rect.left + (rect.width / 2) - (tooltipWidth / 2);
    arrowPos = 'top';
  } 
  // 尝试放在上方
  else if (rect.top - tooltipHeight - margin > 0) {
    top = rect.top - tooltipHeight - margin;
    left = rect.left + (rect.width / 2) - (tooltipWidth / 2);
    arrowPos = 'bottom';
  }
  // 放在左侧
  else {
    top = rect.top;
    left = rect.left - tooltipWidth - margin;
    arrowPos = 'right'; // Arrow points right (tooltip is on left)
  }

  // 边界修正 (X轴)
  if (left < 10) left = 10;
  if (left + tooltipWidth > window.innerWidth - 10) left = window.innerWidth - tooltipWidth - 10;

  tooltipStyle.value = {
    top: `${top}px`,
    left: `${left}px`
  };

  // 箭头样式
  if (arrowPos === 'top') {
    arrowClass.value = '-top-2 left-1/2 -translate-x-1/2 bg-white border-t border-l border-medical-200 rotate-45';
  } else if (arrowPos === 'bottom') {
    arrowClass.value = '-bottom-2 left-1/2 -translate-x-1/2 bg-white border-b border-r border-medical-200 rotate-45';
  } else {
     // 简化处理其他方向
    arrowClass.value = 'hidden';
  }
};

const nextStep = () => {
  if (currentStepIndex.value < steps.length - 1) {
    currentStepIndex.value++;
  } else {
    finishTutorial();
  }
};

const skipTutorial = () => {
  finishTutorial();
};

const finishTutorial = () => {
  isActive.value = false;
  localStorage.setItem(STORAGE_KEY, 'true');
};

const startTutorial = () => {
  // 检查是否已完成
  if (localStorage.getItem(STORAGE_KEY)) return;
  
  // 延迟一点启动，等待 UI 渲染完成
  setTimeout(() => {
    isActive.value = true;
    updatePosition();
  }, 1000);
};

// 监听窗口大小变化重新定位
watch([width, height, currentStepIndex], updatePosition);

// 暴露给外部调用（例如手动重新开始教程）
const restart = () => {
  currentStepIndex.value = 0;
  isActive.value = true;
  updatePosition();
};

onMounted(() => {
  startTutorial();
});

defineExpose({ restart });
</script>