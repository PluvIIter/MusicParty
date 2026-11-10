<template>
    <div ref="pickerRef" class="relative shrink-0" @keydown.esc="onEscape" @focusout="onFocusOut">
        <button
            ref="triggerRef"
            type="button"
            title="切换主题"
            aria-label="切换主题"
            :aria-expanded="isOpen"
            :aria-controls="panelId"
            class="theme-picker-button flex h-9 w-9 items-center justify-center border transition-colors hover:border-accent hover:bg-surface-sunken hover:text-ink focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
            :class="
                isOpen ? 'bg-surface-sunken border-accent text-ink' : 'bg-surface-raised border-line text-ink-muted'
            "
            @click="togglePicker"
        >
            <Palette class="h-[18px] w-[18px]" aria-hidden="true" />
        </button>

        <div
            v-if="isOpen"
            :id="panelId"
            ref="panelRef"
            role="region"
            aria-label="选择主题"
            class="theme-picker-panel fixed inset-x-3 top-16 z-[100] max-h-[calc(100dvh-5rem)] overflow-y-auto border border-line bg-surface-raised p-4 text-ink shadow-xl sm:absolute sm:inset-x-auto sm:right-0 sm:top-full sm:mt-3 sm:w-80"
        >
            <div class="mb-4 flex items-center justify-between gap-3 border-b border-line pb-3">
                <h2 class="text-sm font-bold tracking-wide">选择主题</h2>
                <span class="text-xs text-ink-muted">即时切换</span>
            </div>

            <section
                v-for="group in themeGroups"
                :key="group.mode"
                :aria-labelledby="`${panelId}-${group.mode}`"
                class="mb-4 last:mb-0"
            >
                <h3 :id="`${panelId}-${group.mode}`" class="mb-2 text-xs font-medium text-ink-muted">
                    {{ group.label }}
                </h3>
                <div class="grid grid-cols-2 gap-2">
                    <button
                        v-for="theme in group.themes"
                        :key="theme.id"
                        type="button"
                        :data-theme-id="theme.id"
                        :aria-pressed="ui.themeId === theme.id"
                        class="theme-picker-button min-w-0 border p-2.5 text-left transition-colors hover:border-accent focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
                        :class="
                            ui.themeId === theme.id
                                ? 'border-accent bg-accent/10 text-ink'
                                : 'border-line bg-surface hover:bg-surface-sunken text-ink'
                        "
                        @click="ui.setTheme(theme.id)"
                    >
                        <span class="mb-2 flex h-5 overflow-hidden border border-line" aria-hidden="true">
                            <span
                                v-for="(swatch, index) in theme.swatches"
                                :key="index"
                                class="flex-1"
                                :style="{ backgroundColor: swatch }"
                            ></span>
                        </span>
                        <span class="flex min-w-0 items-center justify-between gap-1.5">
                            <span class="truncate text-xs font-medium">{{ theme.name }}</span>
                            <Check
                                v-if="ui.themeId === theme.id"
                                class="h-3.5 w-3.5 shrink-0 text-accent"
                                aria-hidden="true"
                            />
                        </span>
                    </button>
                </div>
            </section>
        </div>
    </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId } from 'vue';
import { Check, Palette } from 'lucide-vue-next';
import { THEMES } from '../constants/themes';
import { useUiStore } from '../stores/ui';

const ui = useUiStore();
const isOpen = ref(false);
const pickerRef = ref(null);
const triggerRef = ref(null);
const panelRef = ref(null);
const panelId = `theme-picker-${useId()}`;
const themeGroups = computed(() =>
    [
        { mode: 'light', label: '浅色主题' },
        { mode: 'dark', label: '深色主题' },
    ].map((group) => ({ ...group, themes: THEMES.filter((theme) => theme.mode === group.mode) }))
);

const togglePicker = async () => {
    isOpen.value = !isOpen.value;
    if (!isOpen.value) return;

    await nextTick();
    if (!isOpen.value) return;
    const currentTheme = panelRef.value?.querySelector('[aria-pressed="true"]');
    (currentTheme || panelRef.value?.querySelector('button'))?.focus();
};

const onEscape = (event) => {
    if (!isOpen.value) return;
    event.preventDefault();
    event.stopPropagation();
    isOpen.value = false;
    triggerRef.value?.focus();
};

let focusTimer;
const onFocusOut = (event) => {
    clearTimeout(focusTimer);
    if (event.relatedTarget && pickerRef.value?.contains(event.relatedTarget)) return;
    // A focus transfer can briefly expose body before the next button receives focus.
    focusTimer = setTimeout(() => {
        if (isOpen.value && !pickerRef.value?.contains(document.activeElement)) isOpen.value = false;
    }, 0);
};

const onPointerDown = (event) => {
    if (isOpen.value && !event.composedPath().includes(pickerRef.value)) {
        isOpen.value = false;
    }
};

onMounted(() => document.addEventListener('pointerdown', onPointerDown, true));
onBeforeUnmount(() => {
    clearTimeout(focusTimer);
    document.removeEventListener('pointerdown', onPointerDown, true);
});
</script>

<style scoped>
.theme-picker-button {
    border-radius: 0 0 8px 0;
    corner-shape: bevel;
}

.theme-picker-panel {
    clip-path: polygon(0 0, 100% 0, 100% calc(100% - 10px), calc(100% - 10px) 100%, 0 100%);
}
</style>
