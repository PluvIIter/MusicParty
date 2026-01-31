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
        class="absolute bg-white border border-medical-200 p-4 shadow-xl transition-all duration-300 chamfer-br flex flex-col gap-3"
        :style="tooltipStyle"
    >
      <div class="flex items-center justify-between border-b border-medical-100 pb-2">
        <span class="text-xs font-mono font-bold text-accent">TUTORIAL_SYSTEM // {{ currentStepIndex + 1 }}/{{ steps.length }}</span>
        <button @click="skipTutorial" class="text-xs text-medical-400 hover:text-medical-900 font-mono">[SKIP]</button>
      </div>
      
      <div class="text-sm font-bold text-medical-900 leading-relaxed">
        {{ currentDisplayContent }}
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
// 标记当前是否在使用移动端目标
const isUsingMobileTarget = ref(false);

const { width, height } = useWindowSize();

const STORAGE_KEY = 'mp_tutorial_done_v1';

const steps = [
  {
    targetId: 'tutorial-rename',
    mobileTargetId: 'tutorial-rename-mobile',
    content: '点击这里可以修改你的昵称，输入后按回车确认。',
    mobileContent: '点击这里打开用户列表，可以修改你的昵称。'
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
    mobileTargetId: 'tutorial-queue-mobile',
    content: '这里是播放队列。悬停在歌曲上可以进行置顶或删除操作。',
    mobileContent: '点击这里查看播放队列。'
  },
  {
    targetId: 'tutorial-pause',
    mobileTargetId: 'tutorial-pause-mobile',
    content: '注意：暂停/播放是全局生效的，会影响所有在线听众，请谨慎操作。'
  },
  {
    targetId: 'tutorial-random',
    mobileTargetId: 'tutorial-random-mobile',
    content: '随机播放模式采用“公平随机”算法，确保每个人点的歌都有均等的机会被播放。'
  },
  {
    targetId: 'tutorial-download',
    mobileTargetId: 'tutorial-download-mobile',
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

const currentDisplayContent = computed(() => {
  if (isUsingMobileTarget.value && currentStep.value.mobileContent) {
    return currentStep.value.mobileContent;
  }
  return currentStep.value.content;
});

const tooltipStyle = ref({});
const arrowClass = ref('');

// 检查元素是否可见（且有大小）
const isElementVisible = (el) => {
  if (!el) return false;
  const rect = el.getBoundingClientRect();
  return rect.width > 0 && rect.height > 0;
};

const updatePosition = async () => {
  if (!isActive.value) return;
  await nextTick();
  
  const step = currentStep.value;
  let el = document.getElementById(step.targetId);
  isUsingMobileTarget.value = false;
  
  // 如果主目标不可见，尝试移动端目标
  if (!isElementVisible(el)) {
    if (step.mobileTargetId) {
      const mobileEl = document.getElementById(step.mobileTargetId);
      if (isElementVisible(mobileEl)) {
        el = mobileEl;
        isUsingMobileTarget.value = true;
      }
    }
  }

  if (!isElementVisible(el)) {
    // 如果都找不到，自动跳过
    console.warn(`Tutorial target ${step.targetId} (or mobile) not found, skipping.`);
    if (currentStepIndex.value < steps.length - 1) {
      currentStepIndex.value++;
      return; 
    } else {
      finishTutorial();
      return;
    }
  }

  const rect = el.getBoundingClientRect();
  targetRect.value = rect;

  // 计算 Tooltip 位置
  // 响应式宽度：PC端默认300，移动端适应屏幕宽度减去边距
  const screenWidth = window.innerWidth;
  const maxWidth = Math.min(300, screenWidth - 32); 
  
  // 估算高度，或者在DOM渲染后获取实际高度（这里先用预估值简化）
  const estimatedHeight = 150; 
  const margin = 16;

  let top, left;
  let arrowPos = '';

  // 策略：
  // 1. 下方
  // 2. 上方
  // 3. 屏幕中间（针对移动端不好定位的情况）

  const spaceBelow = window.innerHeight - rect.bottom;
  const spaceAbove = rect.top;

  if (spaceBelow > estimatedHeight + margin) {
    top = rect.bottom + margin;
    arrowPos = 'top';
  } else if (spaceAbove > estimatedHeight + margin) {
    top = rect.top - estimatedHeight - margin;
    arrowPos = 'bottom';
  } else {
    // 空间不足，强制放在覆盖位置或中间，这里选择偏下或偏上一点
    // 如果是移动端，可能需要更激进的处理
    if (spaceBelow > spaceAbove) {
      top = rect.bottom + margin;
      arrowPos = 'top';
    } else {
      top = rect.top - estimatedHeight - margin;
      arrowPos = 'bottom';
    }
  }

  // 水平居中对齐目标
  left = rect.left + (rect.width / 2) - (maxWidth / 2);

  // 边界修正 (X轴)
  if (left < 16) left = 16;
  if (left + maxWidth > screenWidth - 16) left = screenWidth - maxWidth - 16;

  tooltipStyle.value = {
    top: `${top}px`,
    left: `${left}px`,
    width: `${maxWidth}px`
  };

  // 箭头样式
  // 需要计算箭头相对于 tooltip 的位置，因为 tooltip 可能被推移了
  const arrowLeft = rect.left + (rect.width / 2) - left;
  // 限制箭头位置不要超出 tooltip 圆角范围
  const clampedArrowLeft = Math.max(10, Math.min(maxWidth - 10, arrowLeft));

  if (arrowPos === 'top') {
    arrowClass.value = `-top-2 bg-white border-t border-l border-medical-200 rotate-45`;
    // 动态设置箭头 horizontal 位置
    // 由于 Tailwind 类不能动态插值 left，这里可能需要 style 或者 复杂的类名逻辑
    // 为了简单，我们直接把 style 注入到 style 对象中，或者使用 CSS 变量
    // 这里我们简单起见，如果对齐比较歪，箭头可能对不准。
    // 改进：使用 absolute left 样式
  } else {
    arrowClass.value = `-bottom-2 bg-white border-b border-r border-medical-200 rotate-45`;
  }
  
  // 添加箭头内联样式
  // 注意：我们需要直接操作 DOM 或者增加一个 style 绑定给箭头
  // 为了不破坏模板结构太多，我们将箭头样式也绑定到 style
  // 但 arrowClass 已经在用了。我们修改一下模板中的 style 绑定。
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