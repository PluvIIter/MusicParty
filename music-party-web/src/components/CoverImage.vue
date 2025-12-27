<template>
  <div class="relative overflow-hidden bg-medical-200 flex items-center justify-center">
    <img 
      v-if="!hasError && src" 
      :src="src" 
      class="w-full h-full object-cover transition-opacity duration-500"
      @error="hasError = true"
    />
    <div v-else class="text-medical-400 flex flex-col items-center">
      <div class="border-2 border-medical-400 rounded-sm p-1 mb-1">
        <div class="w-4 h-4 bg-medical-400"></div>
      </div>
      <span class="text-[10px] font-mono tracking-widest">NO SIGNAL</span>
    </div>
    <!-- 扫描线装饰 -->
    <div class="absolute inset-0 bg-[url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAADCAYAAABS3WWCAAAAE0lEQVQYV2NkYGD4zwABjFAQAwBATgMJy2B8NAAAAABJRU5ErkJggg==')] opacity-10 pointer-events-none"></div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue';
const props = defineProps(['src']);
const hasError = ref(false);
// 当 src 变化时重置错误状态
watch(() => props.src, () => hasError.value = false);
</script>