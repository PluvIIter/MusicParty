<template>
  <div class="p-4">
    <div class="mb-4 flex items-center justify-between">
      <h3 class="text-sm font-bold text-medical-400">在线成员</h3>
      <div class="text-xs font-mono bg-accent/10 text-accent px-1">{{ users.length }}</div>
    </div>

    <div class="space-y-3">
      <!-- 自己 -->
      <div
          class="flex items-center gap-3 pb-3 border-b border-medical-200 border-dashed transition-all duration-300 p-2 -mx-2 rounded"
          :class="isEnqueuerById(userStore.userToken) ? 'bg-accent/10 border-accent/30 shadow-sm' : ''"
      >
        <div
            class="w-8 h-8 flex items-center justify-center font-bold text-xs transition-colors rounded-none"
            :class="isEnqueuerById(userStore.userToken) ? 'bg-accent text-white' : 'bg-medical-900 text-white'"
        >
          <span v-if="isEnqueuerById(userStore.userToken)">DJ</span>
          <span v-else>ME</span>
        </div>
        <div class="flex-1 min-w-0">
          <input
              v-model="newName"
              @blur="doRename"
              @keyup.enter="doRename"
              class="w-full bg-transparent border-b border-transparent focus:border-accent outline-none text-sm font-bold"
              :class="isEnqueuerById(userStore.userToken) ? 'text-accent' : 'text-medical-900'"
          />
        </div>

        <!-- 使用 CSS 类控制动画 -->
        <div v-if="isEnqueuerById(userStore.userToken)" class="flex gap-0.5 items-end h-4">
          <div class="bar bar-1 bg-accent"></div>
          <div class="bar bar-2 bg-accent"></div>
          <div class="bar bar-3 bg-accent"></div>
        </div>
        <div v-else class="w-2 h-2 bg-green-500 rounded-full animate-pulse"></div>
      </div>

      <!-- 其他人 -->
      <div
          v-for="u in others"
          :key="u.sessionId"
          class="flex items-center gap-3 transition-all duration-300 p-2 -mx-2 rounded"
          :class="[
              isEnqueuerById(u.token) ? 'opacity-100 bg-accent/5' :
              isLikedUser(u.token) ? 'opacity-90 bg-accent/5' : 'opacity-60' // 点赞者背景
          ]"
      >
        <div
            class="w-8 h-8 flex items-center justify-center font-bold text-xs transition-colors rounded-none"
            :class="[
                isEnqueuerById(u.token) ? 'bg-accent text-white' :
                isLikedUser(u.token) ? 'bg-accent text-white' :
                'bg-medical-200 text-medical-500'
            ]"
        >
          <span v-if="isEnqueuerById(u.token)">DJ</span>
          <Zap v-else-if="isLikedUser(u.token)" class="w-4 h-4 fill-white text-white" />
          <span v-else>OP</span>
        </div>
        <div
            class="text-sm font-bold truncate flex-1"
            :class="isEnqueuerById(u.token) ? 'text-accent' : 'text-medical-900'"
        >
          {{ u.name }}
        </div>

        <!-- 使用 CSS 类控制动画 -->
        <div v-if="isEnqueuerById(u.token)" class="flex gap-0.5 items-end h-4">
          <div class="bar bar-1 bg-accent"></div>
          <div class="bar bar-2 bg-accent"></div>
          <div class="bar bar-3 bg-accent"></div>
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
import { Zap } from 'lucide-vue-next';

const userStore = useUserStore();
const playerStore = usePlayerStore();
const users = computed(() => userStore.onlineUsers);
const me = computed(() => userStore.currentUser);
const newName = ref(me.value.name);

const isLikedUser = (token) => {
  if (!playerStore.nowPlaying) return false;
  return playerStore.nowPlaying.likedUserIds?.includes(token);
};

watch(() => me.value.name, (n) => newName.value = n);

const others = computed(() => users.value.filter(u => u.token !== userStore.userToken));

const doRename = () => {
  if(newName.value && newName.value !== me.value.name) {
    playerStore.renameUser(newName.value);
  }
};

const isEnqueuerById = (token) => {
  if (!playerStore.nowPlaying) return false;
  // 后端 NowPlayingInfo 现在存的是 enqueuedById (Token)
  // 我们比较：这首歌的Token === 列表里该用户的Token
  return playerStore.nowPlaying.enqueuedById === token;
};
</script>

<style scoped>
/* 使用标准 CSS 实现跳动效果 */
.bar {
  width: 3px;
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