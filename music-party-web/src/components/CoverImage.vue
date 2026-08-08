<template>
  <div class="relative overflow-hidden bg-medical-200 flex items-center justify-center">
    <img
        v-if="!hasError && src"
        :src="src"
        class="w-full h-full object-cover transition-opacity duration-500"
        @error="hasError = true"
    />

    <!-- 🟢 修改部分开始：使用 SVG 图标代替固定像素的 DIV -->
    <div v-else class="flex flex-col items-center justify-center w-full h-full text-medical-400/80 p-1">
      <!--
         1. w-1/2 h-1/2: 让图标大小始终占据容器的一半，完美适配 32px 或 300px
         2. stroke-width: 线条稍微细一点，看起来更精致
      -->
      <ImageOff class="w-1/2 h-1/2" :stroke-width="1.5" />

      <!-- 只有在容器足够大时（比如主播放器），才通过 CSS 容器查询或简单的 Hidden 逻辑显示文字？
           为了简单且不破坏布局，我们可以移除文字，或者把文字做得非常小且允许隐藏。
           鉴于列表很小，直接只显示图标是最清晰的。
      -->
    </div>
    <!-- 🟢 修改部分结束 -->

    <!-- 扫描线装饰：默认显示（播放器/中间视觉区大封面保留）；
         搜索页等小缩略图通过 :scanline="false" 关闭 -->
    <div v-if="scanline" class="absolute inset-0 bg-[url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAADCAYAAABS3WWCAAAAE0lEQVQYV2NkYGD4zwABjFAQAwBATgMJy2B8NAAAAABJRU5ErkJggg==')] opacity-10 pointer-events-none"></div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue';
// 🟢 新增：引入图标
import { ImageOff } from 'lucide-vue-next';

const props = defineProps({
  src: String,
  // 是否显示扫描线横纹：播放器/中间视觉区大封面默认保留，搜索页小封面通过 :scanline="false" 关闭
  scanline: { type: Boolean, default: true }
});
const hasError = ref(false);
// 当 src 变化时重置错误状态
watch(() => props.src, () => hasError.value = false);
</script>