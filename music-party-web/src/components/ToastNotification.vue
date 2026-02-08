<template>
  <TransitionGroup
      tag="div"
      enter-active-class="transition duration-300 ease-out"
      enter-from-class="translate-y-2 opacity-0 scale-95"
      enter-to-class="translate-y-0 opacity-100 scale-100"
      leave-active-class="transition duration-200 ease-in"
      leave-from-class="translate-y-0 opacity-100 scale-100"
      leave-to-class="translate-y-2 opacity-0 scale-95"
      class="fixed top-10 left-1/2 -translate-x-1/2 z-[100] flex flex-col items-center gap-2 pointer-events-none"
  >
    <div
        v-for="toast in toastStore.toasts"
        :key="toast.id"
        class="bg-medical-900/90 text-white px-6 py-3 shadow-xl backdrop-blur-sm flex items-center gap-3 min-w-[300px] border-l-4 pointer-events-auto cursor-pointer"
        :class="getTypeClass(toast.type)"
        @click="toastStore.remove(toast.id)"
    >
      <component :is="getIcon(toast.type)" class="w-5 h-5 flex-shrink-0" />
      <div class="flex-1 min-w-0">
        <div class="font-bold text-sm font-mono">{{ toast.title }}</div>
        <div v-if="toast.message" class="text-xs text-medical-200 truncate max-w-[250px]">{{ toast.message }}</div>
      </div>
    </div>
  </TransitionGroup>
</template>

<script setup>
import { useToastStore } from '../stores/toast';
import { CheckCircle, AlertCircle, Info, AlertTriangle } from 'lucide-vue-next';

const toastStore = useToastStore();

const getTypeClass = (type) => {
  switch (type) {
    case 'success': return 'border-accent';
    case 'error': return 'border-red-500';
    case 'warning': return 'border-yellow-500';
    default: return 'border-medical-400';
  }
};

const getIcon = (type) => {
  switch (type) {
    case 'success': return CheckCircle;
    case 'error': return AlertCircle;
    case 'warning': return AlertTriangle;
    default: return Info;
  }
};
</script>