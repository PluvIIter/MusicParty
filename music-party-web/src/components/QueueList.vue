<template>
  <div id="tutorial-queue" class="h-full flex flex-col bg-white border-l border-medical-200">
    <div class="p-4 bg-medical-50 border-b border-medical-200 flex flex-wrap gap-2 justify-between items-center">
      <h3 class="text-sm font-bold text-medical-900">播放队列 <span class="text-accent text-xs">[{{ queue.length }}]</span></h3>
    </div>

    <div v-if="!(player.isShuffle && player.isFairShuffle) && claimableGroups.length"
        class="p-2 border-b border-medical-200 space-y-2 max-h-44 overflow-y-auto">
      <div class="text-[10px] text-medical-500">离线成员的歌单 · 认领后同时改名</div>
      <div v-for="group in claimableGroups" :key="group.token" class="flex items-center gap-2 text-xs">
        <span class="flex-1 min-w-0 break-words">{{ group.name }} · {{ group.totalCount }} 首</span>
        <button type="button" @click="claimGroup(group)" :disabled="!!claiming || !player.connected"
            :aria-label="`认领 ${group.name} 的歌单并改名`"
            class="shrink-0 border border-accent/30 text-accent px-2 py-1 disabled:opacity-40">
          {{ claiming === group.token ? '认领中…' : '认领并改名' }}
        </button>
      </div>
    </div>

    <!-- Conditional Rendering: Show empty message -->
    <div v-if="queue.length === 0" class="flex-1 text-center py-10 text-xs font-mono text-medical-400">
      QUEUE EMPTY / WAITING FOR INPUT
    </div>

    <!-- Shuffle Mode View: Grouped by User (ONLY for Fair Shuffle) -->
    <div v-else-if="player.isShuffle && player.isFairShuffle" class="flex-1 overflow-y-auto p-2">
      <!-- Shuffle Indicator -->
      <div class="mb-4 bg-accent/10 border border-accent/20 p-2 flex items-center gap-2 rounded-sm text-accent text-xs font-bold">
        <Shuffle class="w-4 h-4" />
        <span>随机播放中</span>
      </div>

      <!-- Top Songs (Always at top) -->
      <div v-if="topItems.length > 0" class="mb-4">
        <div class="text-xs font-mono text-medical-400 mb-2 pl-1">TOP PRIORITY</div>
        <QueueItem
            v-for="(item, idx) in topItems"
            :key="item.queueId"
            :item="item"
            :index="idx"
        />
      </div>

      <!-- User Groups (Drawers) -->
      <div class="space-y-2">
        <div v-for="group in userGroups" :key="group.token" class="border border-medical-100 bg-white">
          <div class="flex items-center">
          <button type="button"
              @click="toggleUser(group.token)"
              class="min-w-0 flex-1 flex items-center justify-between p-3 hover:bg-medical-50 transition-colors"
          >
            <div class="min-w-0 flex items-center gap-3">
              <div class="shrink-0 w-8 h-8 rounded bg-medical-100 flex items-center justify-center text-medical-500">
                <User class="w-4 h-4" />
              </div>
              <div class="min-w-0 text-left">
                <div class="text-sm font-bold text-medical-800 break-words">
                  {{ userStore.resolveName(group.token, group.name) }}
                </div>
                <div class="text-[10px] text-medical-400 font-mono">
                  {{ group.totalCount }} SONGS <span v-if="canClaim(group)">· 离线</span>
                </div>
              </div>
            </div>
            <component :is="expandedUsers[group.token] ? ChevronDown : ChevronRight" class="w-4 h-4 text-medical-400" />
          </button>
          <button v-if="canClaim(group)" type="button" @click="claimGroup(group)"
              :disabled="!!claiming || !player.connected" :aria-label="`认领 ${group.name} 的歌单并改名`"
              title="将这份歌单合并到自己名下，并改为原主人的昵称；刚断线需等 10 秒"
              class="shrink-0 mr-2 border border-accent/30 text-accent text-xs px-2 py-1 disabled:opacity-40">
            {{ claiming === group.token ? '认领中…' : '认领并改名' }}
          </button>
          </div>

          <!-- Drawer Content -->
          <div v-show="expandedUsers[group.token]" class="bg-medical-50/50 p-2 border-t border-medical-100">
            <QueueItem
                v-for="(item, idx) in group.items"
                :key="item.queueId"
                :item="item"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- Normal Mode: Virtual List -->
    <div v-else v-bind="containerProps" class="flex-1 overflow-y-auto p-2">
      <div v-bind="wrapperProps">
        <QueueItem
            v-for="{ data: item, index } in list"
            :key="item.queueId"
            :item="item"
            :index="index"
        />
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue';
import { useVirtualList } from '@vueuse/core';
import { usePlayerStore } from '../stores/player';
import { Shuffle, ChevronDown, ChevronRight, User } from 'lucide-vue-next';
import { useUserStore } from '../stores/user';
import QueueItem from './QueueItem.vue';
import { useToast } from '../composables/useToast';

const player = usePlayerStore();
const queue = computed(() => player.queue);
const userStore = useUserStore();
const claiming = ref('');
const toast = useToast();
const canClaim = (group) => player.config.allowPlaylistClaim === true && group.token !== userStore.userToken && group.token !== 'SYSTEM'
  && !group.token?.startsWith('__') && !group.isGuest
  && !userStore.onlineUsers.some(user => user.token === group.token);
const claimableGroups = computed(() => userGroups.value.filter(canClaim));
const claimGroup = async (group) => {
  if (claiming.value || !canClaim(group)) return;
  claiming.value = group.token;
  try {
    const result = await player.claimPlaylist(group.token);
    expandedUsers.value = { ...expandedUsers.value, [userStore.userToken]: true };
    toast.success(result.message);
  } catch (error) { toast.error(error.message || '认领失败，请重试'); }
  finally { claiming.value = ''; }
};

// Virtual List Setup (used for normal mode)
const { list, containerProps, wrapperProps } = useVirtualList(queue, {
  itemHeight: 64,
  overscan: 10,
});

// --- Shuffle Mode Logic ---

const topItems = computed(() => {
  return queue.value.filter(item => item.priority === 'GLOBAL_TOP');
});

const userGroups = computed(() => {
  const groupsMap = new Map();

  queue.value.forEach(item => {
    const token = item.enqueuedBy.token;
    if (!groupsMap.has(token)) {
      groupsMap.set(token, {
        token: token,
        name: item.enqueuedBy.name,
        isGuest: item.enqueuedBy.isGuest,
        totalCount: 0,
        items: []
      });
    }
    const group = groupsMap.get(token);
    group.totalCount++;
    if (item.enqueuedBy.isGuest || item.music?.platform === 'netease-fm') group.isGuest = true;
    if (item.priority !== 'GLOBAL_TOP') group.items.push(item);
  });

  // 对每个组内的歌曲进行排序：个人置顶 (USER_TOP) 放在最前面
  return Array.from(groupsMap.values()).filter(group => group.items.length || canClaim(group)).map(group => ({
    ...group,
    items: [...group.items].sort((a, b) => {
      const aIsUserTop = a.priority === 'USER_TOP';
      const bIsUserTop = b.priority === 'USER_TOP';
      if (aIsUserTop && !bIsUserTop) return -1;
      if (!aIsUserTop && bIsUserTop) return 1;
      return 0; // 保持原有相对顺序
    })
  }));
});

const expandedUsers = ref({});

const toggleUser = (token) => {
  // Use simple boolean toggle. Need to ensure reactivity works.
  // Directly setting property on object might require spread if it was not initialized.
  expandedUsers.value = {
    ...expandedUsers.value,
    [token]: !expandedUsers.value[token]
  };
};
</script>
