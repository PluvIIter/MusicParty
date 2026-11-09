<template>
    <button type="button" @click="open" class="text-xs font-bold text-medical-500 hover:text-accent py-2">
        音乐缓存
    </button>
    <Teleport to="body">
        <div
            v-if="visible"
            class="fixed inset-0 z-[350] bg-black/40 flex items-center justify-center p-4"
            @click.self="visible = false"
        >
            <section
                role="dialog"
                aria-modal="true"
                aria-label="音乐缓存"
                class="w-full max-w-sm bg-white border border-medical-200 shadow-xl p-5 space-y-4"
            >
                <div class="flex items-center justify-between gap-3">
                    <h2 class="font-bold text-medical-900">音乐缓存</h2>
                    <button
                        type="button"
                        aria-label="关闭音乐缓存设置"
                        @click="visible = false"
                        class="text-medical-500 p-2"
                    >
                        ✕
                    </button>
                </div>
                <p v-if="loading" class="text-sm text-medical-500">正在读取…</p>
                <template v-else-if="stats.available">
                    <p class="text-2xl font-bold text-medical-900">{{ formatBytes(stats.bytes) }}</p>
                    <p class="text-sm text-medical-500">
                        {{ stats.files }} 份网易云音频含已保存片段。重复播放优先读取本机缓存，音质保持原样。
                    </p>
                    <p class="text-xs text-medical-500">
                        容量不设人为上限；浏览器空间不足时淘汰旧缓存。缓存只存在当前设备和当前网址，不预先下载其他歌曲。
                    </p>
                    <button
                        type="button"
                        @click="clear"
                        :disabled="busy || stats.bytes === 0"
                        class="w-full border border-medical-300 text-medical-700 py-2 font-bold text-sm disabled:opacity-40"
                    >
                        {{ busy ? '正在清除…' : '清除音乐缓存' }}
                    </button>
                    <p class="text-xs text-medical-500">
                        只清除本机音频缓存，保留身份、账号设置与房间歌单。继续听歌会重新缓存。
                    </p>
                </template>
                <p v-else class="text-sm text-medical-500">
                    此浏览器暂未启用音乐缓存。请通过 HTTPS 邀请网址打开，刷新后重试；当前继续正常在线播放。
                </p>
                <p v-if="message" role="status" class="text-xs text-accent">{{ message }}</p>
            </section>
        </div>
    </Teleport>
</template>

<script setup>
import { ref } from 'vue';
import { requestAudioCache } from '../services/audioCache';

const visible = ref(false),
    loading = ref(false),
    busy = ref(false);
const stats = ref({ available: false, bytes: 0, files: 0 });
const message = ref('');
const formatBytes = (bytes) => (bytes >= 1e9 ? `${(bytes / 1e9).toFixed(2)} GB` : `${(bytes / 1e6).toFixed(1)} MB`);
const open = async () => {
    visible.value = true;
    loading.value = true;
    message.value = '';
    try {
        stats.value = await requestAudioCache();
    } catch (error) {
        message.value = error.message;
    } finally {
        loading.value = false;
    }
};
const clear = async () => {
    busy.value = true;
    message.value = '';
    try {
        stats.value = await requestAudioCache('clear');
        message.value = stats.value.available ? '音乐缓存已清除' : stats.value.error || '清除失败，请稍后重试';
    } catch (error) {
        message.value = error.message;
    } finally {
        busy.value = false;
    }
};
</script>
