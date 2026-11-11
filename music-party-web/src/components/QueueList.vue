<template>
    <div id="tutorial-queue" class="h-full flex flex-col bg-surface-raised border-l border-line">
        <div class="p-4 bg-surface border-b border-line flex flex-wrap gap-2 justify-between items-center">
            <h3 class="text-sm font-bold text-ink">
                播放队列 <span class="text-accent text-xs">[{{ queue.length }}]</span>
            </h3>
            <div class="flex items-center gap-2">
                <button
                    v-if="player.isRoundRobin"
                    type="button"
                    :disabled="!player.connected || userStore.isGuest"
                    :aria-label="`我的选曲：${memberShuffle ? '随机' : '顺序'}`"
                    :title="
                        memberShuffle
                            ? '轮到我时随机选曲（点击改为顺序）'
                            : '轮到我时按我的歌单顺序选曲（点击改为随机）'
                    "
                    class="p-1.5 border border-line text-ink-muted hover:text-accent hover:border-accent disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                    @click="toggleMemberShuffle"
                >
                    <component :is="memberShuffle ? Shuffle : ListOrdered" class="w-4 h-4" aria-hidden="true" />
                </button>
                <button
                    type="button"
                    @click="askClear"
                    :disabled="clearing || !player.connected || userStore.isGuest || mineCount === 0"
                    aria-label="清空我的歌单"
                    title="清空我的歌单（只移除自己点的歌）"
                    class="p-1.5 border border-line text-ink-muted hover:text-accent hover:border-accent disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                >
                    <Trash2 class="w-4 h-4" />
                </button>
            </div>
        </div>
        <p class="sr-only" role="status" aria-live="polite">{{ reorder.announcement.value }}</p>

        <div
            v-if="player.isShuffle && queue.length"
            class="px-3 py-2 bg-accent/10 text-accent text-xs font-bold flex items-center gap-2"
        >
            <Shuffle class="w-4 h-4" aria-hidden="true" />随机播放中
        </div>
        <!-- Conditional Rendering: Show empty message -->
        <div v-if="queue.length === 0" class="flex-1 text-center py-10 text-xs font-mono text-ink-subtle">
            QUEUE EMPTY / WAITING FOR INPUT
        </div>

        <!-- Round-robin member groups -->
        <div v-else-if="player.isRoundRobin" data-queue-scroll class="flex-1 overflow-y-auto p-2">
            <!-- Round-robin indicator -->
            <div
                class="-mx-2 -mt-2 mb-3 bg-accent/10 border-b border-accent/20 px-3 py-2 flex items-center gap-2 text-accent text-xs font-bold"
            >
                <Repeat class="w-4 h-4" />
                <span>轮询播放中</span>
            </div>

            <!-- Top Songs (Always at top) -->
            <div v-if="topItems.length > 0" class="mb-4">
                <div class="text-xs font-mono text-ink-subtle mb-2 pl-1">TOP PRIORITY</div>
                <QueueItem v-for="(item, idx) in topItems" :key="item.queueId" :item="item" />
            </div>

            <!-- User Groups (Drawers) -->
            <div class="space-y-2">
                <div v-for="group in userGroups" :key="group.token" class="border border-line bg-surface-raised">
                    <div class="flex items-center">
                        <div
                            role="button"
                            tabindex="0"
                            @click="toggleUser(group.token)"
                            @keydown.enter="toggleUser(group.token)"
                            @keydown.space.prevent="toggleUser(group.token)"
                            class="min-w-0 flex-1 flex items-center justify-between p-3 hover:bg-surface transition-colors cursor-pointer"
                        >
                            <div class="min-w-0 flex items-center gap-3">
                                <div
                                    class="shrink-0 w-8 h-8 rounded bg-surface-sunken flex items-center justify-center text-ink-muted"
                                >
                                    <User class="w-4 h-4" />
                                </div>
                                <div class="min-w-0 text-left">
                                    <div class="text-sm font-bold text-ink break-words">
                                        {{ userStore.resolveName(group.token, group.name) }}
                                    </div>
                                    <div class="text-[10px] text-ink-subtle font-mono">
                                        {{ group.totalCount }} SONGS
                                        <template v-if="canClaim(group)">
                                            ·
                                            <button
                                                type="button"
                                                @click.stop="askClaim(group)"
                                                :disabled="!!claiming || !player.connected"
                                                :aria-label="`认领 ${group.name} 的歌单`"
                                                class="text-accent hover:underline disabled:opacity-40"
                                            >
                                                {{ claiming === group.token ? '认领中…' : '离线·点击认领' }}
                                            </button>
                                        </template>
                                    </div>
                                </div>
                            </div>
                            <component
                                :is="expandedUsers[group.token] ? ChevronDown : ChevronRight"
                                class="w-4 h-4 text-ink-subtle"
                            />
                        </div>
                    </div>

                    <!-- Drawer Content -->
                    <div v-show="expandedUsers[group.token]" class="bg-surface/50 p-2 border-t border-line">
                        <QueueItem v-for="item in group.items" :key="item.queueId" :item="item" />
                    </div>
                </div>
            </div>
        </div>

        <!-- Normal Mode: Virtual List -->
        <div v-else v-bind="containerProps" data-queue-scroll class="flex-1 overflow-y-auto p-2">
            <div v-bind="wrapperProps">
                <QueueItem v-for="{ data: item, index } in list" :key="item.queueId" :item="item" />
            </div>
        </div>

        <!-- 离线歌单：默认收起，放到底部，避免占据队列主区域 -->
        <div
            v-if="!player.isRoundRobin && claimableGroups.length"
            class="flex-shrink-0 border-t border-line bg-surface"
        >
            <button
                type="button"
                @click="claimPanelOpen = !claimPanelOpen"
                :aria-expanded="claimPanelOpen"
                class="w-full flex items-center justify-between gap-2 px-3 py-2 text-[10px] font-mono text-ink-subtle hover:text-ink transition-colors"
            >
                <span class="flex items-center gap-1.5">
                    <User class="w-3 h-3" />
                    离线歌单
                    <span class="text-accent">[{{ claimableGroups.length }}]</span>
                </span>
                <span class="flex items-center gap-1.5">
                    {{ claimPanelOpen ? '收起' : '点击认领' }}
                    <component :is="claimPanelOpen ? ChevronDown : ChevronRight" class="w-3 h-3" />
                </span>
            </button>

            <div v-if="claimPanelOpen" class="max-h-44 overflow-y-auto border-t border-line">
                <button
                    v-for="group in claimableGroups"
                    :key="group.token"
                    type="button"
                    @click="askClaim(group)"
                    :disabled="!!claiming || !player.connected"
                    :aria-label="`认领 ${group.name} 的歌单`"
                    class="w-full flex items-center gap-3 px-3 py-2 text-left border-b border-line last:border-b-0 hover:bg-surface-sunken disabled:opacity-40 transition-colors group"
                >
                    <div class="shrink-0 w-7 h-7 bg-surface-sunken flex items-center justify-center text-ink-muted">
                        <User class="w-3.5 h-3.5" />
                    </div>
                    <div class="min-w-0 flex-1">
                        <div class="text-xs font-bold text-ink truncate">{{ group.name }}</div>
                        <div class="text-[10px] font-mono text-ink-subtle">{{ group.totalCount }} SONGS · 离线</div>
                    </div>
                    <span class="shrink-0 text-[10px] font-bold text-accent group-hover:underline">{{
                        claiming === group.token ? '认领中…' : '认领'
                    }}</span>
                </button>
            </div>
        </div>
        <!-- 认领确认 -->
        <Teleport to="body">
            <div
                v-if="pendingClaim"
                class="fixed inset-0 z-[300] bg-brand/40 flex items-center justify-center p-4"
                @click.self="pendingClaim = null"
            >
                <div
                    role="dialog"
                    aria-modal="true"
                    aria-label="确认认领歌单"
                    class="w-full max-w-sm bg-surface-raised border border-line shadow-xl p-5 space-y-4"
                >
                    <h3 class="font-bold text-ink">认领歌单</h3>
                    <p class="text-xs text-ink-muted leading-relaxed">
                        将 <span class="font-bold text-ink">{{ pendingClaim.name }}</span> 的歌单（{{
                            pendingClaim.totalCount
                        }}
                        首）合并到自己名下，同时更改为此昵称。
                    </p>
                    <div class="flex justify-end gap-2">
                        <button
                            type="button"
                            @click="pendingClaim = null"
                            class="px-3 py-1.5 text-xs border border-line text-ink-muted hover:text-ink transition-colors"
                        >
                            取消
                        </button>
                        <button
                            type="button"
                            @click="confirmClaim"
                            :disabled="!!claiming"
                            class="px-3 py-1.5 text-xs font-bold bg-accent text-accent-contrast disabled:opacity-40 transition-colors"
                        >
                            {{ claiming ? '认领中…' : '确认认领' }}
                        </button>
                    </div>
                </div>
            </div>
        </Teleport>

        <!-- 清空确认 -->
        <Teleport to="body">
            <div
                v-if="pendingClear"
                class="fixed inset-0 z-[300] bg-brand/40 flex items-center justify-center p-4"
                @click.self="pendingClear = false"
            >
                <div
                    role="dialog"
                    aria-modal="true"
                    aria-label="确认清空我的歌单"
                    class="w-full max-w-sm bg-surface-raised border border-line shadow-xl p-5 space-y-4"
                >
                    <h3 class="font-bold text-ink">清空我的歌单</h3>
                    <p class="text-xs text-ink-muted leading-relaxed">
                        将自己点的 {{ mineCount }} 首歌曲从播放列表移除。
                    </p>
                    <div class="flex justify-end gap-2">
                        <button
                            type="button"
                            @click="pendingClear = false"
                            class="px-3 py-1.5 text-xs border border-line text-ink-muted hover:text-ink transition-colors"
                        >
                            取消
                        </button>
                        <button
                            type="button"
                            @click="confirmClear"
                            :disabled="clearing"
                            class="px-3 py-1.5 text-xs font-bold bg-accent text-accent-contrast hover:bg-accent-hover disabled:opacity-40 transition-colors"
                        >
                            {{ clearing ? '正在清空…' : '确认清空' }}
                        </button>
                    </div>
                </div>
            </div>
        </Teleport>
    </div>
</template>

<script setup>
import { computed, provide, ref } from 'vue';
import { useVirtualList } from '@vueuse/core';
import { usePlayerStore } from '../stores/player';
import { Shuffle, Repeat, ListOrdered, ChevronDown, ChevronRight, User, Trash2 } from 'lucide-vue-next';
import { useUserStore } from '../stores/user';
import QueueItem from './QueueItem.vue';
import { useToast } from '../composables/useToast';
import { splitQueue } from '../domain/queueRules';
import { useQueueReorder } from '../composables/useQueueReorder';

const player = usePlayerStore();
const queue = computed(() => player.queue);
const userStore = useUserStore();
const memberShuffle = computed(() => player.memberShufflePreferences[userStore.userToken] ?? true);
const reorder = useQueueReorder(player, userStore);
provide('queueReorder', reorder);
const claiming = ref('');
const clearing = ref(false);
const mineCount = computed(() => queue.value.filter((item) => item.enqueuedBy.token === userStore.userToken).length);
const toast = useToast();
// 个人选曲偏好只影响自己，切换后仅本机弹提示
const toggleMemberShuffle = () => {
    const next = !memberShuffle.value;
    player.setMemberShuffle(next);
    toast.success(next ? '个人选曲：随机' : '个人选曲：顺序');
};
const canClaim = (group) =>
    player.config.allowPlaylistClaim === true &&
    group.token !== userStore.userToken &&
    group.token !== 'SYSTEM' &&
    !group.token?.startsWith('__') &&
    !group.isGuest &&
    !userStore.onlineUsers.some((user) => user.token === group.token);
const claimableGroups = computed(() => userGroups.value.filter(canClaim));
// 离线歌单默认收起：认领不是高频操作，不该占据队列主区域
const claimPanelOpen = ref(false);
// 点击「离线·点击认领」先弹确认，避免误触把别人的歌单并过来
const pendingClaim = ref(null);
const askClaim = (group) => {
    if (claiming.value || !player.connected || !canClaim(group)) return;
    pendingClaim.value = group;
};
const confirmClaim = async () => {
    const group = pendingClaim.value;
    if (!group) return;
    await claimGroup(group);
    pendingClaim.value = null;
};
// 清空自己的歌单：先确认再执行
const pendingClear = ref(false);
const askClear = () => {
    if (clearing.value || !mineCount.value) return;
    pendingClear.value = true;
};
const confirmClear = async () => {
    await clearMine();
    pendingClear.value = false;
};
const claimGroup = async (group) => {
    if (claiming.value || !canClaim(group)) return;
    claiming.value = group.token;
    try {
        const result = await player.claimPlaylist(group.token);
        expandedUsers.value = { ...expandedUsers.value, [userStore.userToken]: true };
        toast.success(result.message);
    } catch (error) {
        toast.error(error.message || '认领失败，请重试');
    } finally {
        claiming.value = '';
    }
};
const clearMine = async () => {
    if (clearing.value || !mineCount.value) return;
    clearing.value = true;
    try {
        const result = await player.clearMyPlaylist();
        toast.success(result.message);
    } catch (error) {
        toast.error(error.message || '清空失败，请重试');
    } finally {
        clearing.value = false;
    }
};

// Virtual List Setup (used for normal mode)
const { list, containerProps, wrapperProps } = useVirtualList(queue, {
    itemHeight: 64,
    overscan: 10,
});

// Round-robin groups

const queueGroups = computed(() => splitQueue(queue.value, (group) => group.items.length > 0 || canClaim(group)));
const topItems = computed(() => queueGroups.value.topItems);
const userGroups = computed(() => queueGroups.value.userGroups);

const expandedUsers = ref({});

const toggleUser = (token) => {
    // Use simple boolean toggle. Need to ensure reactivity works.
    // Directly setting property on object might require spread if it was not initialized.
    expandedUsers.value = {
        ...expandedUsers.value,
        [token]: !expandedUsers.value[token],
    };
};
</script>
