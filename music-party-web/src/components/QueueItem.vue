<template>
    <div
        class="group relative flex items-center gap-2 p-2 border hover:border-line-strong transition-all mb-2 h-14"
        :data-queue-id="item.queueId"
        :class="[
            isFmMarker ? 'bg-accent/5 border-accent/30' : 'bg-surface-raised border-line',
            reorder?.drag.value?.queueId === item.queueId ? 'opacity-50' : '',
        ]"
    >
        <!-- 拖动插入提示：填充卡片之间的间隔（不是卡片内部） -->
        <div
            v-if="isDropTarget"
            class="pointer-events-none absolute inset-x-0 -top-2 h-2 bg-accent"
            aria-hidden="true"
        ></div>
        <!-- 插入到队尾：最后一首卡片下方的间隔 -->
        <div
            v-if="isTailTarget"
            class="pointer-events-none absolute inset-x-0 -bottom-2 h-2 bg-accent"
            aria-hidden="true"
        ></div>
        <!-- 可拖动时显示抓手，否则统一显示 #（两者互斥） -->
        <button
            v-if="isReorderable"
            type="button"
            :disabled="!player.connected || userStore.isGuest"
            :aria-label="`调整 ${item.music.name} 的顺序`"
            :title="reorderTooltip"
            class="shrink-0 w-6 flex items-center justify-center p-1 touch-none cursor-grab active:cursor-grabbing text-ink-subtle hover:text-accent disabled:opacity-40 disabled:cursor-not-allowed"
            @pointerdown="reorder?.start(item, $event)"
            @keydown="reorder?.keyboard(item, $event)"
        >
            <GripVertical class="w-4 h-4" aria-hidden="true" />
        </button>
        <div v-else class="w-6 text-center font-mono text-xs text-ink-subtle">#</div>

        <div class="flex-1 min-w-0">
            <div class="flex items-center min-w-0">
                <div class="text-sm font-bold text-ink truncate">{{ item.music.name }}</div>
                <span
                    v-if="isFmMarker"
                    class="ml-1 flex-shrink-0 px-1 bg-accent text-accent-contrast text-[8px] font-mono font-bold"
                    >FM</span
                >
            </div>
            <div class="flex justify-between items-center">
                <div v-if="!item.status || item.status === 'READY'" class="text-xs text-ink-muted truncate">
                    {{ item.music.artists[0] }}
                </div>

                <!-- 下载中状态：显示闪烁的 LOADING -->
                <div
                    v-else-if="item.status === 'DOWNLOADING' || item.status === 'PENDING'"
                    class="text-xs font-mono font-bold text-accent animate-pulse flex items-center gap-1"
                >
                    <Loader2 class="w-3 h-3 animate-spin" /> LOADING...
                </div>

                <!-- 失败状态 -->
                <div v-else-if="item.status === 'FAILED'" class="text-xs font-mono font-bold text-danger">
                    DOWNLOAD FAILED
                </div>
                <div class="text-[10px] text-ink-subtle bg-surface px-1 border border-line">
                    {{ userStore.resolveName(item.enqueuedBy.token, item.enqueuedBy.name) }}
                </div>
            </div>
        </div>

        <!-- 操作遮罩 -->
        <div
            v-if="!userStore.isGuest && !isFmMarker"
            class="absolute inset-y-0 right-0 bg-surface-raised/90 px-2 flex items-center gap-2 opacity-0 group-hover:opacity-100 transition-opacity"
        >
            <button @click="player.topSong(item.queueId)" title="Top" class="p-1 hover:text-accent">
                <ArrowUpToLine class="w-4 h-4" />
            </button>
            <button @click="player.removeSong(item.queueId)" title="Remove" class="p-1 hover:text-danger">
                <Trash2 class="w-4 h-4" />
            </button>
        </div>

        <!-- 标记置顶的歌曲 -->
        <div
            v-if="item.priority === 'GLOBAL_TOP'"
            class="absolute top-0 right-0 w-2 h-2 bg-accent"
            title="全局置顶"
        ></div>
        <div
            v-else-if="item.priority === 'USER_TOP'"
            class="absolute top-0 right-0 w-3 h-3 bg-accent [clip-path:polygon(100%_0,0_0,100%_100%)]"
            title="个人置顶"
        ></div>
        <!-- 拖动时跟手的浮层：卡片内容跟随指针 -->
        <Teleport to="body">
            <div
                v-if="isDragged"
                class="pointer-events-none fixed z-[200] w-[264px] will-change-transform"
                :style="ghostStyle"
                aria-hidden="true"
            >
                <div
                    class="flex items-center gap-2 p-2 h-14 bg-surface-raised border border-accent shadow-2xl"
                >
                    <span class="shrink-0 w-6 flex items-center justify-center text-accent">
                        <GripVertical class="w-4 h-4" />
                    </span>
                    <div class="flex-1 min-w-0">
                        <div class="flex items-center min-w-0">
                            <div class="text-sm font-bold text-ink truncate">{{ item.music.name }}</div>
                            <span
                                v-if="isFmMarker"
                                class="ml-1 flex-shrink-0 px-1 bg-accent text-accent-contrast text-[8px] font-mono font-bold"
                                >FM</span
                            >
                        </div>
                        <div class="text-xs text-ink-muted truncate">{{ item.music.artists[0] }}</div>
                    </div>
                    <div class="text-[10px] text-ink-subtle bg-surface px-1 border border-line shrink-0">
                        {{ userStore.resolveName(item.enqueuedBy.token, item.enqueuedBy.name) }}
                    </div>
                </div>
            </div>
        </Teleport>
    </div>
</template>

<script setup>
import { computed, inject } from 'vue';
import { canReorderItem } from '../domain/queueRules';
import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import { Trash2, ArrowUpToLine, Loader2, GripVertical } from 'lucide-vue-next';

const props = defineProps({
    item: {
        type: Object,
        required: true,
    },
});

const player = usePlayerStore();
const userStore = useUserStore();
const isFmMarker = computed(() => props.item.music.platform === 'netease-fm');
const reorder = inject('queueReorder', null);
const dragState = computed(() => reorder?.drag.value ?? null);
const isDragged = computed(() => dragState.value?.queueId === props.item.queueId);
const ghostStyle = computed(() => {
    const drag = dragState.value;
    if (!drag || drag.x === undefined) return { display: 'none' };
    return { left: `${drag.x + 14}px`, top: `${drag.y - 28}px` };
});
const isTailTarget = computed(
() => {
    const drag = reorder?.drag.value;
    if (!drag?.hasTarget || drag.beforeQueueId !== null) return false;
    const rows = document.querySelectorAll('[data-queue-id]');
    return rows[rows.length - 1]?.dataset.queueId === props.item.queueId;
});
const isDropTarget = computed(

    () => !!reorder?.drag.value?.hasTarget && reorder.drag.value.beforeQueueId === props.item.queueId
);
const isReorderable = computed(
() => canReorderItem(props.item, userStore.userToken, player.playMode));
const reorderTooltip = computed(() => {
    if (player.playMode === 'SEQUENTIAL')
        return '拖动或方向键上/下调整所有成员普通歌曲的全局顺序；置顶、FM 和系统条目位置不变';
    if (player.playMode === 'ROUND_ROBIN')
        return '拖动或方向键上/下调整自己歌单的普通歌曲；仅交换自己的槽位，其他成员和置顶、FM 条目不变';
    return '当前模式仅可拖动或用方向键上/下调整自己的普通歌曲，不能移动其他成员歌曲；不改变随机选曲或单曲循环规则';
});
</script>
