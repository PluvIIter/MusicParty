<template>
    <div v-if="!passed" class="fixed inset-0 z-[200] bg-surface flex items-center justify-center p-4">
        <div class="bg-surface-raised p-8 shadow-2xl border border-line w-full max-w-md chamfer-br relative">
            <!-- 装饰 -->
            <div class="absolute top-0 left-0 w-2 h-full bg-brand"></div>

            <div class="mb-6">
                <h2 class="text-2xl font-black text-ink tracking-tighter">
                    {{ isSetupMode ? 'INITIALIZE SYSTEM' : 'PbSECURITY ACCESS' }}
                </h2>
                <p class="text-xs font-mono text-ink-muted mt-1">
                    {{ isSetupMode ? 'PLEASE CONFIGURE ROOM ACCESS.' : 'RESTRICTED AREA. ENTER PASSCODE.' }}
                </p>
            </div>

            <div class="space-y-4">
                <input
                    v-if="!isSetupMode || (isSetupMode && setupType === 'password')"
                    v-model="inputPassword"
                    type="password"
                    :placeholder="isSetupMode ? 'SET NEW PASSWORD' : 'INPUT PASSWORD'"
                    @keyup.enter="handleAction"
                    class="w-full bg-surface border border-line p-3 outline-none focus:border-accent font-mono text-center tracking-widest text-lg"
                    autofocus
                />

                <button
                    v-if="!isSetupMode || setupType === 'password'"
                    @click="handleAction"
                    :disabled="loading"
                    class="w-full bg-brand text-ink-inverse font-bold py-3 hover:bg-accent transition-colors disabled:opacity-50"
                >
                    {{ loading ? 'VERIFYING...' : isSetupMode ? 'CONFIRM PASSWORD' : 'UNLOCK' }}
                </button>

                <div v-if="isSetupMode && setupType === 'initial'" class="space-y-3">
                    <button
                        @click="setupType = 'password'"
                        class="w-full bg-brand text-ink-inverse font-bold py-3 hover:bg-accent transition-colors chamfer-br"
                    >
                        SET PASSWORD PROTECTION
                    </button>

                    <div class="relative flex py-2 items-center">
                        <div class="flex-grow border-t border-line"></div>
                        <span class="flex-shrink-0 mx-4 text-ink-subtle text-xs font-mono">OR</span>
                        <div class="flex-grow border-t border-line"></div>
                    </div>

                    <button
                        @click="setupNoPassword"
                        class="w-full bg-surface-raised border border-line-strong text-ink-muted font-bold py-3 hover:bg-surface-sunken transition-colors hover:text-ink"
                    >
                        NO PASSWORD (PUBLIC)
                    </button>
                </div>

                <button
                    v-if="isSetupMode && setupType === 'password'"
                    @click="setupType = 'initial'"
                    class="w-full text-xs text-ink-subtle hover:text-ink mt-2 underline"
                >
                    &lt; BACK
                </button>
            </div>

            <div v-if="errorMsg" class="mt-4 text-center text-red-500 font-mono text-xs animate-pulse">
                > ERROR: {{ errorMsg }}
            </div>
        </div>
    </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { authApi } from '../api/auth';
import { STORAGE_KEYS } from '../constants/storage';

const emit = defineEmits(['unlocked']);

const passed = ref(false);
const isSetupMode = ref(false);
const setupType = ref('initial'); // 'initial' | 'password'
const inputPassword = ref('');
const errorMsg = ref('');
const loading = ref(false);

const checkStatus = async () => {
    loading.value = true;
    try {
        // 🟢 修复点：直接获取数据，不再需要 .data
        // 我们的 api/client.js 里的拦截器已经帮我们把 data 取出来了
        const data = await authApi.getStatus();
        const { isSetup, hasProtection } = data;

        if (!isSetup) {
            isSetupMode.value = true;
        } else {
            if (!hasProtection) {
                passed.value = true;
                emit('unlocked');
            } else {
                const cachedPass = localStorage.getItem(STORAGE_KEYS.ROOM_PASSWORD);
                if (cachedPass) {
                    await verify(cachedPass, true);
                }
            }
        }
    } catch (e) {
        console.error('Auth Status Error:', e); // 在控制台打印真实错误
        errorMsg.value = 'CONNECTION FAILED';
    } finally {
        loading.value = false;
    }
};

const verify = async (pwd, isAuto = false) => {
    try {
        await authApi.verify(pwd);
        localStorage.setItem(STORAGE_KEYS.ROOM_PASSWORD, pwd);
        passed.value = true;
        emit('unlocked');
    } catch (e) {
        if (!isAuto) {
            errorMsg.value = 'INVALID PASSWORD';
            inputPassword.value = '';
        } else {
            localStorage.removeItem(STORAGE_KEYS.ROOM_PASSWORD);
        }
    }
};

const setup = async () => {
    if (!inputPassword.value) {
        errorMsg.value = 'PASSWORD CANNOT BE EMPTY';
        return;
    }
    await performSetup(inputPassword.value);
};

const setupNoPassword = async () => {
    await performSetup('');
};

const performSetup = async (pwd) => {
    loading.value = true;
    try {
        await authApi.setup(pwd);
        if (pwd) localStorage.setItem(STORAGE_KEYS.ROOM_PASSWORD, pwd);
        passed.value = true;
        emit('unlocked');
    } catch (e) {
        errorMsg.value = 'SETUP FAILED';
        loading.value = false;
    }
};

const handleAction = () => {
    if (loading.value) return;
    errorMsg.value = '';
    if (isSetupMode.value) {
        setup();
    } else {
        verify(inputPassword.value);
    }
};

onMounted(() => {
    checkStatus();
});
</script>
