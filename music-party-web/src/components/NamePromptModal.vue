<!-- File: music-party-web/src/components/NamePromptModal.vue -->
<template>
  <div v-if="userStore.showNameModal" class="fixed inset-0 z-[300] bg-medical-900/90 backdrop-blur-sm flex items-center justify-center p-4">
    <div class="bg-white p-6 w-full max-w-sm chamfer-br shadow-2xl relative">
      <!-- 装饰条 -->
      <div class="absolute top-0 left-0 w-2 h-full bg-accent"></div>

      <h2 class="text-xl font-black text-medical-900 mb-2">IDENTIFICATION REQUIRED</h2>
      <p class="text-xs font-sans text-medical-500 mb-6 leading-relaxed">
        在操作之前，先给自己取个名字吧<br>
        也可以在用户列表中点击自己的名字重命名
      </p>

      <input
          v-model="inputName"
          @keyup.enter="confirm"
          placeholder="ENTER CODENAME"
          class="w-full bg-medical-50 border border-medical-200 p-3 outline-none focus:border-accent font-bold mb-4 text-medical-900 placeholder-medical-300"
          autofocus
      />
      
      <div v-if="errorMsg" class="text-xs text-red-500 font-bold mb-4 animate-pulse">{{ errorMsg }}</div>

      <div class="flex gap-2">
        <button @click="userStore.showNameModal = false" class="flex-1 py-3 text-xs font-bold text-medical-400 hover:bg-medical-50">
          CANCEL
        </button>
        <button @click="confirm" class="flex-1 bg-medical-900 text-white font-bold py-3 hover:bg-accent transition-colors">
          CONFIRM
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue';
import { useUserStore } from '../stores/user';
import { usePlayerStore } from '../stores/player';

const userStore = useUserStore();
const playerStore = usePlayerStore();
const inputName = ref('');
const errorMsg = ref('');

watch(inputName, () => errorMsg.value = '');

const confirm = () => {
  const name = inputName.value.trim();
  if(!name) return;

  if (name.toLowerCase().startsWith('guest') || name.startsWith('游客')) {
    errorMsg.value = '不能使用“游客”作为正式名字';
    return;
  }
  
  // 调用 renameUser，等待后端 socket 确认后关闭
  playerStore.renameUser(name);
};
</script>