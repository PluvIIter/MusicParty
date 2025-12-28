// File Path: music-party-web\src\components\UserList.vue

<template>
  <div class="p-4">
    <div class="mb-4 flex items-center justify-between">
      <h3 class="font-mono text-xs font-bold text-medical-400">在线成员</h3>
      <div class="text-xs font-mono bg-accent/10 text-accent px-1">{{ users.length }}</div>
    </div>

    <div class="space-y-3">
      <!-- 自己 -->
      <div
          class="flex items-center gap-3 pb-3 border-b border-medical-200 border-dashed transition-all duration-300 p-2 -mx-2 rounded"
          :class="isEnqueuer(me.name) ? 'bg-accent/10 border-accent/30 shadow-sm' : ''"
      >
        <div
            class="w-8 h-8 flex items-center justify-center font-bold text-xs transition-colors"
            :class="isEnqueuer(me.name) ? 'bg-accent text-white' : 'bg-medical-900 text-white'"
        >
          <span v-if="isEnqueuer(me.name)">DJ</span>
          <span v-else>ME</span>
        </div>
        <div class="flex-1 min-w-0">
          <input
              v-model="newName"
              @blur="doRename"
              @keyup.enter="doRename"
              class="w-full bg-transparent border-b border-transparent focus:border-accent outline-none text-sm font-bold"
          />
        </div>

        <!-- 🟢 修改：使用 CSS 类控制动画 -->
        <div v-if="isEnqueuer(me.name)" class="flex gap-0.5 items-end h-4">
          <div class="bar bar-1"></div>
          <div class="bar bar-2"></div>
          <div class="bar bar-3"></div>
        </div>
        <div v-else class="w-2 h-2 bg-green-500 rounded-full animate-pulse"></div>
      </div>

      <!-- 其他人 -->
      <div
          v-for="u in others"
          :key="u.sessionId"
          class="flex items-center gap-3 transition-all duration-300 p-2 -mx-2 rounded"
          :class="isEnqueuer(u.name) ? 'opacity-100 bg-accent/5' : 'opacity-60'"
      >
        <div
            class="w-8 h-8 flex items-center justify-center font-bold text-xs transition-colors"
            :class="isEnqueuer(u.name) ? 'bg-accent text-white' : 'bg-medical-200 text-medical-500'"
        >
          <span v-if="isEnqueuer(u.name)">DJ</span>
          <span v-else>OP</span>
        </div>
        <div
            class="text-sm font-bold truncate flex-1"
            :class="isEnqueuer(u.name) ? 'text-accent' : ''"
        >
          {{ u.name }}
        </div>

        <!-- 🟢 修改：使用 CSS 类控制动画 -->
        <div v-if="isEnqueuer(u.name)" class="flex gap-0.5 items-end h-4">
          <div class="bar bar-1"></div>
          <div class="bar bar-2"></div>
          <div class="bar bar-3"></div>
        </div>
        <div v-else class="w-2 h-2 bg-medical-300 rounded-full"></div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue';
import { useUserStore } from '../stores/user';
import { usePlayerStore } from '../stores/player';

const userStore = useUserStore();
const playerStore = usePlayerStore();
const users = computed(() => userStore.onlineUsers);
const me = computed(() => userStore.currentUser);
const newName = ref(me.value.name);

watch(() => me.value.name, (n) => newName.value = n);

const others = computed(() => users.value.filter(u => u.sessionId !== me.value.sessionId));

const doRename = () => {
  if(newName.value && newName.value !== me.value.name) {
    playerStore.renameUser(newName.value);
  }
};

const isEnqueuer = (name) => {
  if (!playerStore.nowPlaying) return false;
  return playerStore.nowPlaying.enqueuedBy === name;
};
</script>

<style scoped>
/* 🟢 使用标准 CSS 实现跳动效果 */
.bar {
  width: 3px;
  background-color: #F97316; /* accent orange */
  border-radius: 1px;
  /* 使用 transform 性能更好 */
  transform-origin: bottom;
  animation: bounce infinite ease-in-out;
}

.bar-1 { animation-duration: 0.6s; height: 60%; }
.bar-2 { animation-duration: 0.8s; height: 100%; }
.bar-3 { animation-duration: 0.5s; height: 40%; }

@keyframes bounce {
  0%, 100% { transform: scaleY(0.4); }
  50% { transform: scaleY(1); }
}
</style>