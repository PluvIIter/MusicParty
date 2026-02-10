<template>
  <div id="tutorial-queue" class="h-full flex flex-col bg-white border-l border-medical-200">
    <div class="p-4 bg-medical-50 border-b border-medical-200 flex justify-between items-center">
      <h3 class="text-sm font-bold text-medical-900">播放队列 <span class="text-accent text-xs">[{{ queue.length }}]</span></h3>
    </div>

    <!-- Conditional Rendering: Show empty message -->
    <div v-if="queue.length === 0" class="flex-1 text-center py-10 text-xs font-mono text-medical-400">
      QUEUE EMPTY / WAITING FOR INPUT
    </div>

    <!-- Shuffle Mode View: Grouped by User -->
    <div v-else-if="player.isShuffle" class="flex-1 overflow-y-auto p-2">
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
          <button
              @click="toggleUser(group.token)"
              class="w-full flex items-center justify-between p-3 hover:bg-medical-50 transition-colors"
          >
            <div class="flex items-center gap-3">
              <div class="w-8 h-8 rounded bg-medical-100 flex items-center justify-center text-medical-500">
                <User class="w-4 h-4" />
              </div>
              <div class="text-left">
                <div class="text-sm font-bold text-medical-800">
                  {{ userStore.resolveName(group.token, group.name) }}
                </div>
                <div class="text-[10px] text-medical-400 font-mono">
                  {{ group.items.length }} SONGS
                </div>
              </div>
            </div>
            <component :is="expandedUsers[group.token] ? ChevronDown : ChevronRight" class="w-4 h-4 text-medical-400" />
          </button>

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

const player = usePlayerStore();
const queue = computed(() => player.queue);
const userStore = useUserStore();

// Virtual List Setup (used for normal mode)
const { list, containerProps, wrapperProps } = useVirtualList(queue, {
  itemHeight: 64,
  overscan: 10,
});

// --- Shuffle Mode Logic ---

const topItems = computed(() => {
  return queue.value.filter(item => item.queueId.startsWith('TOP-'));
});

const userGroups = computed(() => {
  const normalItems = queue.value.filter(item => !item.queueId.startsWith('TOP-'));
  const groupsMap = new Map();

  normalItems.forEach(item => {
    const token = item.enqueuedBy.token;
    if (!groupsMap.has(token)) {
      groupsMap.set(token, {
        token: token,
        name: item.enqueuedBy.name,
        items: []
      });
    }
    groupsMap.get(token).items.push(item);
  });

  // 对每个组内的歌曲进行排序：个人置顶 (USERTOP-) 放在最前面
  return Array.from(groupsMap.values()).map(group => ({
    ...group,
    items: [...group.items].sort((a, b) => {
      const aIsUserTop = a.queueId.startsWith('USERTOP-');
      const bIsUserTop = b.queueId.startsWith('USERTOP-');
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