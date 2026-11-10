<template>
    <div class="h-24 bg-surface-raised border-t border-line flex items-center px-4 md:px-8 relative z-50 shadow-lg">
        <!-- 封面 -->
        <div
            id="tutorial-source"
            @click="openSourcePage"
            class="w-16 h-16 md:w-20 md:h-20 -mt-6 md:mt-0 shadow-lg border-2 border-white dark:border-graphic chamfer-br flex-shrink-0 relative z-10 bg-brand cursor-pointer group overflow-hidden"
            title="Open Source Page"
        >
            <CoverImage
                :src="nowPlaying?.music.coverUrl"
                class="w-full h-full transition-transform duration-300 group-hover:scale-110 group-hover:opacity-50"
            />

            <!-- 悬浮时的遮罩和图标 -->
            <div
                class="absolute inset-0 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity duration-300 bg-black/40"
            >
                <ExternalLink class="w-6 h-6 text-ink-inverse" />
            </div>
        </div>

        <!-- 中间：信息与进度 -->
        <div class="flex-1 ml-4 mr-4 md:mr-8 flex flex-col justify-center min-w-0">
            <div class="flex justify-between items-end mb-1">
                <div class="overflow-hidden w-full">
                    <!-- 标题显示逻辑与样式 -->
                    <h2
                        class="text-lg font-bold truncate leading-tight transition-colors duration-300"
                        :class="!player.connected ? 'text-warning animate-pulse' : 'text-ink'"
                    >
                        {{
                            !player.connected
                                ? '!CONNECTION LOST!'
                                : nowPlaying
                                  ? nowPlaying.music.name
                                  : 'WAITING FOR SIGNAL...'
                        }}
                    </h2>

                    <!-- 副标题显示逻辑与样式 -->
                    <p
                        class="text-xs font-sans truncate transition-colors duration-300"
                        :class="!player.connected ? 'text-warning animate-pulse' : 'text-ink/60'"
                    >
                        {{
                            !player.connected
                                ? 'RECONNECT SERVER...'
                                : nowPlaying
                                  ? nowPlaying.music.artists.join(' / ')
                                  : 'SYSTEM STANDBY'
                        }}
                    </p>
                </div>

                <!-- 时间显示 -->
                <div class="hidden md:block font-mono text-xs text-ink/60 flex-shrink-0 ml-2">
                    <span v-if="player.isLoading" class="text-accent animate-pulse">SYNCING SERVER...</span>
                    <span v-if="player.isBuffering" class="animate-pulse text-accent">BUFFERING...</span>
                    <span v-else
                        >{{ formatDuration(player.localProgress) }} /
                        {{ formatDuration(nowPlaying?.music.duration || 0) }}</span
                    >
                </div>
            </div>

            <!-- 进度条（管理面板开启 allowSeek 后可拖动，房间内所有人同步跳转） -->
            <div
                ref="barRef"
                class="h-1 bg-line w-full relative touch-none"
                :class="canSeek ? 'cursor-pointer mp-seek' : ''"
                :role="canSeek ? 'slider' : undefined"
                :tabindex="canSeek ? 0 : undefined"
                :aria-label="canSeek ? '播放进度' : undefined"
                :aria-valuemin="canSeek ? 0 : undefined"
                :aria-valuemax="canSeek ? Math.round((nowPlaying?.music?.duration || 0) / 1000) : undefined"
                :aria-valuenow="canSeek ? Math.round(dragging ? dragPercent : progressPercent) : undefined"
                :title="canSeek ? '拖动可跳转（房间同步）；方向键 ±5 秒，Shift ±15 秒' : ''"
                @pointerdown="onSeekPointerDown"
                @pointermove="onSeekPointerMove"
                @pointerup="onSeekPointerUp"
                @pointercancel="onSeekPointerCancel"
                @keydown="onSeekKeydown"
            >
                <div
                    v-for="(marker, index) in likeMarkers"
                    :key="index"
                    class="absolute z-20 transition-all duration-300 transform -translate-y-1/2 top-1/2"
                    :style="{ left: (marker / (nowPlaying?.music.duration || 1)) * 100 + '%' }"
                >
                    <Zap class="w-3 h-3 drop-shadow-md" :class="'text-accent fill-accent'" />
                </div>

                <div
                    class="h-full relative"
                    :class="[
                        player.isErrorState ? 'bg-danger/100' : 'bg-accent',
                        dragging ? '' : 'transition-all duration-300 ease-linear',
                    ]"
                    :style="{ width: (dragging ? dragPercent : progressPercent) + '%' }"
                >
                    <div
                        v-if="!player.isErrorState"
                        class="absolute right-0 top-1/2 -translate-y-1/2 w-2 h-2 rotate-45 bg-accent"
                        :class="dragging ? 'scale-150' : 'transition-all duration-300'"
                    ></div>
                </div>
            </div>

            <!-- 移动端简易控制 -->
            <div class="flex md:hidden justify-end gap-3 mt-2">
                <button
                    id="tutorial-download-mobile"
                    @click="downloadCurrentMusic"
                    class="p-2 bg-surface border border-line rounded-sm text-ink-muted active:bg-line"
                >
                    <Download class="w-4 h-4" />
                </button>
                <PlayModePicker
                    id="tutorial-random-mobile"
                    :model-value="player.playMode"
                    :disabled="!player.connected || player.isPlayModeLocked || userStore.isGuest"
                    @select="player.setPlayMode"
                />
                <button
                    id="tutorial-pause-mobile"
                    @click="player.togglePause"
                    :disabled="player.isPauseLocked && !player.isPaused"
                    class="p-2 bg-surface-sunken rounded-sm disabled:opacity-50"
                >
                    <Lock v-if="player.isPauseLocked && !player.isPaused" class="w-4 h-4 text-ink-subtle" />
                    <template v-else>
                        <Play v-if="player.isPaused" class="w-4 h-4" />
                        <Pause v-else class="w-4 h-4" />
                    </template>
                </button>
                <button
                    @click="player.playNext"
                    :disabled="isSkipDisabled"
                    class="p-2 bg-surface-sunken rounded-sm disabled:opacity-50 relative"
                >
                    <SkipForward class="w-4 h-4" />
                    <!-- Badge -->
                    <div
                        v-if="player.isVoteSkipEnabled && canVote && player.currentVotes > 0"
                        class="absolute -top-1 -right-1 bg-accent text-accent-contrast text-[8px] font-bold px-1 min-w-[12px] h-3 flex items-center justify-center rounded-sm shadow-sm"
                    >
                        {{ player.currentVotes }}
                    </div>
                </button>
            </div>
        </div>

        <!-- PC端：右侧控制区 -->
        <div class="hidden md:flex items-center gap-6 flex-shrink-0">
            <!-- 播放控制 -->
            <div class="flex items-center gap-4 border-r border-line pr-6">
                <!-- 新增：下载按钮 (放在 Shuffle 旁边或者 Next 后面) -->
                <button
                    id="tutorial-download"
                    @click="downloadCurrentMusic"
                    class="text-ink-subtle hover:text-accent transition-colors"
                    title="Download"
                >
                    <Download class="w-5 h-5" />
                </button>

                <PlayModePicker
                    id="tutorial-random"
                    :model-value="player.playMode"
                    :disabled="!player.connected || player.isPlayModeLocked || userStore.isGuest"
                    @select="player.setPlayMode"
                />

                <button
                    id="tutorial-pause"
                    @click="player.togglePause"
                    :disabled="player.isPauseLocked && !player.isPaused"
                    class="w-10 h-10 bg-brand text-ink-inverse flex items-center justify-center hover:bg-accent hover:text-accent-contrast transition-colors chamfer-tl disabled:opacity-50 disabled:hover:bg-brand disabled:hover:text-ink-inverse disabled:cursor-not-allowed"
                >
                    <Lock v-if="player.isPauseLocked && !player.isPaused" class="w-4 h-4" />
                    <template v-else>
                        <Play v-if="player.isPaused" class="w-4 h-4 fill-current" />
                        <Pause v-else class="w-4 h-4 fill-current" />
                    </template>
                </button>

                <button
                    @click="player.playNext"
                    :disabled="isSkipDisabled"
                    class="text-ink hover:text-accent transition-colors disabled:opacity-50 disabled:cursor-not-allowed relative group/skip"
                    :title="skipBtnTitle"
                >
                    <SkipForward class="w-6 h-6 fill-current" />
                    <!-- Badge -->
                    <div
                        v-if="player.isVoteSkipEnabled && canVote && player.currentVotes > 0"
                        class="absolute -top-1 -right-1 bg-accent text-accent-contrast text-[10px] font-bold px-1 min-w-[14px] h-3.5 flex items-center justify-center rounded-sm shadow-sm"
                    >
                        {{ player.currentVotes }}
                    </div>
                    <!-- Wait Timer Hint -->
                    <div
                        v-if="player.isVoteSkipEnabled && canVote && waitTimeLeft > 0"
                        class="absolute -bottom-6 left-1/2 -translate-x-1/2 bg-brand text-ink-inverse text-[8px] px-1 py-0.5 rounded-sm opacity-0 group-hover/skip:opacity-100 transition-opacity whitespace-nowrap"
                    >
                        {{ waitTimeLeft }}s 后可投票
                    </div>
                </button>
            </div>

            <!-- 音量控制 -->
            <div class="flex items-center gap-2 group">
                <button @click="toggleMute" class="text-ink-muted hover:text-ink transition-colors">
                    <VolumeX v-if="ui.volume === 0" class="w-5 h-5" />
                    <Volume1 v-else-if="ui.volume < 0.5" class="w-5 h-5" />
                    <Volume2 v-else class="w-5 h-5" />
                </button>

                <!-- 滑块容器 -->
                <div
                    ref="volumeTrackRef"
                    class="w-24 h-6 flex items-center relative cursor-pointer touch-none"
                    @mousedown="handleVolumeMouseDown"
                >
                    <!-- 灰色轨道 -->
                    <div class="w-full h-1 bg-line relative">
                        <!-- 橙色填充层 -->
                        <div
                            class="h-full bg-line-strong group-hover:bg-accent transition-colors relative"
                            :style="{ width: ui.volume * 100 + '%' }"
                        >
                            <!-- 装饰滑块 (只在悬停时显示) -->
                            <div
                                class="absolute right-0 top-1/2 -translate-y-1/2 w-2 h-3 bg-brand group-hover:bg-accent transition-colors scale-0 group-hover:scale-100"
                            ></div>
                        </div>
                    </div>
                </div>

                <div class="w-8 text-[10px] font-mono text-ink-subtle text-right">
                    {{ Math.round(ui.volume * 100) }}%
                </div>
            </div>
        </div>
    </div>
</template>

<script setup>
import { ref, computed, watch, onUnmounted } from 'vue';
import { usePlayerStore } from '../stores/player';
import { useUiStore } from '../stores/ui';
import { useUserStore } from '../stores/user';
import { formatDuration } from '../domain/time';
import * as voteRules from '../domain/voteRules';
import {
    Download,
    SkipForward,
    Play,
    Pause,
    Volume2,
    Volume1,
    VolumeX,
    ExternalLink,
    Zap,
    Lock,
} from 'lucide-vue-next';
import CoverImage from './CoverImage.vue';
import PlayModePicker from './PlayModePicker.vue';
import { useToast } from '../composables/useToast';

const player = usePlayerStore();
const ui = useUiStore();
const userStore = useUserStore();
const volumeTrackRef = ref(null);
const { info, error } = useToast();

const nowPlaying = computed(() => player.nowPlaying);
const likeMarkers = computed(() => nowPlaying.value?.likeMarkers || []);

// 投票切歌相关计算
const hasTrack = computed(() => !!nowPlaying.value);

const canVote = computed(() =>
    voteRules.canVote({
        hasTrack: hasTrack.value,
        enqueuedById: nowPlaying.value?.enqueuedById,
        userToken: userStore.userToken,
    })
);

const waitTimeLeft = computed(() =>
    voteRules.waitTimeLeft({
        isVoteSkipEnabled: player.isVoteSkipEnabled,
        hasTrack: hasTrack.value,
        localProgressMs: player.localProgress,
        voteSkipWaitTime: player.voteSkipWaitTime,
    })
);

const isSkipDisabled = computed(() =>
    voteRules.isSkipDisabled({
        isSkipLocked: player.isSkipLocked,
        isVoteSkipEnabled: player.isVoteSkipEnabled,
        canVote: canVote.value,
        waitTimeLeft: waitTimeLeft.value,
    })
);

const skipBtnTitle = computed(() => {
    if (player.isSkipLocked) return '切歌功能已被锁定';
    if (player.isVoteSkipEnabled) {
        if (!canVote.value) return '强制切歌';
        if (waitTimeLeft.value > 0) return `${waitTimeLeft.value}s 后可投票`;
        return `投票切歌 (${player.currentVotes}/${player.eligibleUsers})`;
    }
    return 'Next';
});

// 计算进度百分比
const progressPercent = computed(() => {
    if (!nowPlaying.value || nowPlaying.value.music.duration === 0) return 0;
    return Math.min(100, (player.localProgress / nowPlaying.value.music.duration) * 100);
});

// --- 拖动进度条（房间级 seek） ---
// 只有管理员在控制台开启 allowSeek 时才可拖；拖动过程只改本地预览，
// 松手才发一次指令，避免拖动途中刷屏。
const canSeek = computed(
    () =>
        player.config?.allowSeek === true &&
        player.connected &&
        !!player.playbackId &&
        !player.isLoading &&
        !!nowPlaying.value &&
        (nowPlaying.value.music?.duration || 0) > 0 &&
        !player.isErrorState
    // 不检查 isSkipLocked（锁切歌只约束换歌，进度仍可调）；
    // 也不检查 streamActive（网页听众听的仍是点播源，拖动同样有效）
);
const barRef = ref(null);
const dragging = ref(false);
const dragPercent = ref(0);
let dragPointerId = null;
let dragPlaybackId = null;
let dragDuration = 0;

/** 指针位置 → 0~1 的比例 */
const seekRatioFromEvent = (event) => {
    const rect = barRef.value?.getBoundingClientRect();
    if (!rect || rect.width <= 0) return 0;
    return Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width));
};

const endDrag = () => {
    const el = barRef.value;
    if (el?.releasePointerCapture && dragPointerId !== null) {
        try {
            el.releasePointerCapture(dragPointerId);
        } catch {
            /* 指针已释放时忽略 */
        }
    }
    dragPointerId = null;
    dragPlaybackId = null;
    dragDuration = 0;
};

const onSeekPointerDown = (event) => {
    if (!canSeek.value) return;
    dragging.value = true;
    dragPlaybackId = player.playbackId;
    dragDuration = nowPlaying.value.music.duration;
    dragPointerId = event.pointerId;
    dragPercent.value = seekRatioFromEvent(event) * 100;
    try {
        barRef.value?.setPointerCapture?.(event.pointerId);
    } catch {
        /* 不支持指针捕获时退化为普通事件处理 */
    }
};

const onSeekPointerMove = (event) => {
    if (!dragging.value || event.pointerId !== dragPointerId) return;
    dragPercent.value = seekRatioFromEvent(event) * 100;
};

const onSeekPointerUp = (event) => {
    if (!dragging.value || event.pointerId !== dragPointerId) return;
    const ratio = seekRatioFromEvent(event);
    const expectedPlaybackId = dragPlaybackId;
    const duration = dragDuration;
    const valid = canSeek.value && expectedPlaybackId === player.playbackId;
    dragging.value = false;
    endDrag();
    if (valid && duration > 0) player.seekTo(ratio * duration, expectedPlaybackId);
};

const onSeekPointerCancel = () => {
    dragging.value = false;
    endDrag();
};

watch(
    [() => player.playbackId, canSeek],
    () => {
        if (dragging.value && (!canSeek.value || dragPlaybackId !== player.playbackId)) onSeekPointerCancel();
    },
    { flush: 'sync' }
);

/** 键盘微调：当前进度（或拖动预览）基础上平移 deltaMs，并交给服务端裁剪 */
const seekByStep = (deltaMs) => {
    const duration = nowPlaying.value?.music?.duration || 0;
    if (duration <= 0) return;
    const base = dragging.value ? (dragPercent.value / 100) * duration : player.localProgress;
    player.seekTo(Math.max(0, Math.min(duration, base + deltaMs)));
};

const onSeekKeydown = (event) => {
    if (!canSeek.value) return;
    const step = event.shiftKey ? 15000 : 5000;
    if (event.key === 'ArrowLeft') {
        event.preventDefault();
        seekByStep(-step);
    } else if (event.key === 'ArrowRight') {
        event.preventDefault();
        seekByStep(step);
    } else if (event.key === 'Home') {
        event.preventDefault();
        player.seekTo(0);
    } else if (event.key === 'End') {
        event.preventDefault();
        player.seekTo((nowPlaying.value?.music?.duration || 0) - 500);
    }
};

// --- 音量逻辑 ---
const lastVolume = ref(0.5);
const isDraggingVolume = ref(false);

const toggleMute = () => {
    if (ui.volume > 0) {
        lastVolume.value = ui.volume;
        ui.setVolume(0);
    } else {
        ui.setVolume(lastVolume.value > 0 ? lastVolume.value : 0.5);
    }
};

// 音量拖拽
const updateVolumeByMouse = (e) => {
    if (!volumeTrackRef.value) return;
    const rect = volumeTrackRef.value.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const percentage = Math.max(0, Math.min(1, x / rect.width));
    ui.setVolume(parseFloat(percentage.toFixed(2)));
};

const handleVolumeMouseDown = (e) => {
    isDraggingVolume.value = true;
    updateVolumeByMouse(e);
    window.addEventListener('mousemove', handleVolumeMouseMove);
    window.addEventListener('mouseup', handleVolumeMouseUp);
};
const handleVolumeMouseMove = (e) => {
    if (isDraggingVolume.value) updateVolumeByMouse(e);
};
const handleVolumeMouseUp = () => {
    isDraggingVolume.value = false;
    window.removeEventListener('mousemove', handleVolumeMouseMove);
    window.removeEventListener('mouseup', handleVolumeMouseUp);
};

// --- 下载逻辑 ---
const downloadCurrentMusic = async () => {
    if (!nowPlaying.value) return;
    const music = nowPlaying.value.music;
    info(`Starting download: ${music.name}...`);
    try {
        const response = await fetch(music.url);
        if (!response.ok) throw new Error('Network error');
        const blob = await response.blob();
        const blobUrl = window.URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = blobUrl;
        link.download = `${music.name} - ${music.artists[0]}.mp3`;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        window.URL.revokeObjectURL(blobUrl);
    } catch (e) {
        window.open(music.url, '_blank');
        error('Blob download failed, opening new tab.');
    }
};

// 跳转源页面
const openSourcePage = () => {
    if (!nowPlaying.value) return;
    const { platform, id } = nowPlaying.value.music;
    let url = platform === 'netease' ? `https://music.163.com/#/song?id=${id}` : `https://www.bilibili.com/video/${id}`;
    if (url) window.open(url, '_blank');
};

onUnmounted(() => {
    window.removeEventListener('mousemove', handleVolumeMouseMove);
    window.removeEventListener('mouseup', handleVolumeMouseUp);
});
</script>
