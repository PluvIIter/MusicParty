<!-- File: music-party-web/src/components/NamePromptModal.vue -->
<template>
    <div
        v-if="userStore.showNameModal"
        class="fixed inset-0 z-[300] bg-brand/90 backdrop-blur-sm flex items-center justify-center p-4"
    >
        <div class="bg-surface-raised p-6 w-full max-w-sm chamfer-br shadow-2xl relative">
            <!-- 装饰条 -->
            <div class="absolute top-0 left-0 w-2 h-full bg-accent"></div>

            <h2 class="text-xl font-black text-ink mb-2">IDENTIFICATION REQUIRED</h2>
            <p class="text-xs font-sans text-ink-muted mb-6 leading-relaxed">
                在操作之前，先给自己取个名字吧<br />
                也可以在用户列表中点击自己的名字重命名
            </p>

            <input
                v-model="inputName"
                @keyup.enter="confirm"
                placeholder="ENTER CODENAME"
                class="w-full bg-surface border border-line p-3 outline-none focus:border-accent font-bold mb-4 text-ink placeholder-ink-subtle"
                autofocus
            />

            <div v-if="errorMsg || userStore.renameError" class="text-xs text-danger font-bold mb-4 animate-pulse">
                {{ errorMsg || userStore.renameError }}
            </div>

            <div class="flex gap-2">
                <button
                    @click="userStore.showNameModal = false"
                    class="flex-1 py-3 text-xs font-bold text-ink-subtle hover:bg-surface"
                >
                    CANCEL
                </button>
                <button
                    @click="confirm"
                    class="flex-1 bg-brand text-ink-inverse font-bold py-3 hover:bg-accent hover:text-accent-contrast transition-colors"
                >
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

watch(inputName, () => {
    errorMsg.value = '';
    userStore.renameError = '';
});

const confirm = () => {
    userStore.renameError = '';
    const name = inputName.value.trim();
    if (!name) return;

    if (name.toLowerCase().startsWith('guest') || name.startsWith('游客')) {
        errorMsg.value = '不能使用“游客”作为正式名字';
        return;
    }

    // 调用 renameUser，等待后端 socket 确认后关闭
    playerStore.renameUser(name);
};
</script>
