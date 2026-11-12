<template>
    <div
        v-if="isActive"
        class="tutorial-layer fixed inset-0 z-[9999] bg-brand/70 backdrop-blur-sm"
        @pointerdown.self.prevent
    >
        <div
            v-if="view === 'tour' && targetRect"
            class="fixed pointer-events-none border-2 border-accent rounded-sm shadow-[0_0_20px_rgb(var(--mp-accent)/0.35)]"
            :style="highlightStyle"
            aria-hidden="true"
        ></div>
        <section
            ref="dialogRef"
            role="dialog"
            aria-modal="true"
            aria-labelledby="tutorial-title"
            aria-describedby="tutorial-subtitle"
            tabindex="-1"
            class="tutorial-dialog fixed flex min-h-0 flex-col bg-surface-raised border border-line shadow-2xl text-ink outline-none"
            :class="view === 'guide' ? 'tutorial-guide' : 'tutorial-tour'"
            :style="view === 'tour' ? tourStyle : undefined"
        >
            <header class="flex shrink-0 items-start justify-between gap-3 border-b border-line p-3 sm:p-5">
                <div class="min-w-0">
                    <p class="text-[10px] font-mono font-bold text-accent">
                        {{ view === 'tour' ? TUTORIAL_UI.tourLabel : TUTORIAL_UI.guideLabel }}
                    </p>
                    <h2 id="tutorial-title" class="text-lg sm:text-xl font-bold break-words">
                        {{ TUTORIAL_UI.title }}
                    </h2>
                    <p id="tutorial-subtitle" class="text-xs text-ink-muted mt-1">{{ TUTORIAL_UI.subtitle }}</p>
                </div>
                <button
                    type="button"
                    :aria-label="TUTORIAL_UI.closeHelp"
                    class="shrink-0 p-2 border border-line text-ink-muted hover:text-accent focus-visible:ring-2 focus-visible:ring-accent"
                    @click="closeHelp"
                >
                    <X class="w-4 h-4" aria-hidden="true" />
                </button>
            </header>

            <template v-if="view === 'tour' && currentStep">
                <div
                    ref="tourBodyRef"
                    class="min-h-0 flex-1 overflow-y-auto overscroll-contain p-4 sm:p-5 space-y-3"
                    aria-live="polite"
                    aria-atomic="true"
                >
                    <p class="text-xs font-mono text-accent">
                        {{ TUTORIAL_UI.stepLabel }} {{ currentStepIndex + 1 }} / {{ TUTORIAL_STEPS.length }}
                    </p>
                    <h3 class="font-bold text-base">{{ currentStep.title }}</h3>
                    <p
                        v-for="(paragraph, index) in currentBody"
                        :key="index"
                        class="text-sm leading-relaxed text-ink-muted break-words"
                    >
                        {{ paragraph }}
                    </p>
                </div>
                <footer class="shrink-0 border-t border-line p-3 sm:p-4 space-y-3">
                    <div class="flex flex-wrap gap-2">
                        <button
                            type="button"
                            class="tutorial-button text-ink-muted border-line"
                            @click="finishTutorial"
                        >
                            {{ TUTORIAL_UI.skip }}
                        </button>
                        <button type="button" class="tutorial-button text-accent border-line" @click="showGuide">
                            {{ TUTORIAL_UI.openGuide }}
                        </button>
                    </div>
                    <div class="flex justify-between gap-2">
                        <button
                            type="button"
                            :disabled="currentStepIndex === 0"
                            class="tutorial-button border-line disabled:opacity-40"
                            @click="previousStep"
                        >
                            {{ TUTORIAL_UI.previous }}
                        </button>
                        <button
                            type="button"
                            class="tutorial-button border-accent bg-accent text-accent-contrast hover:bg-accent-hover"
                            @click="nextStep"
                        >
                            {{ currentStepIndex === TUTORIAL_STEPS.length - 1 ? TUTORIAL_UI.finish : TUTORIAL_UI.next }}
                        </button>
                    </div>
                </footer>
            </template>

            <template v-else-if="view === 'guide'">
                <div class="shrink-0 p-3 sm:px-5 border-b border-line">
                    <label for="tutorial-guide-search" class="block text-xs font-bold text-ink-muted mb-1">{{
                        TUTORIAL_UI.searchLabel
                    }}</label>
                    <input
                        id="tutorial-guide-search"
                        v-model="query"
                        type="search"
                        :placeholder="TUTORIAL_UI.searchPlaceholder"
                        class="w-full min-w-0 p-2 text-sm bg-surface border border-line outline-none focus:border-accent"
                    />
                </div>
                <div class="tutorial-compact-topics shrink-0 px-3 pb-2 border-b border-line">
                    <select
                        :value="selectedTopic"
                        :aria-label="TUTORIAL_UI.topicNavigation"
                        class="w-full p-2 text-sm bg-surface border border-line"
                        @change="selectTopic($event.target.value)"
                    >
                        <option value="">{{ TUTORIAL_UI.allTopics }}</option>
                        <option v-for="topic in TUTORIAL_TOPICS" :key="topic.id" :value="topic.id">
                            {{ topic.title }}
                        </option>
                    </select>
                </div>
                <nav
                    :aria-label="TUTORIAL_UI.topicNavigation"
                    class="tutorial-wide-topics shrink-0 flex flex-wrap gap-1 p-3 sm:px-5 border-b border-line max-h-[24dvh] overflow-y-auto overscroll-contain"
                >
                    <button
                        type="button"
                        :aria-pressed="selectedTopic === ''"
                        class="tutorial-topic"
                        :class="selectedTopic === '' ? 'bg-accent text-accent-contrast' : 'bg-surface text-ink-muted'"
                        @click="selectTopic('')"
                    >
                        {{ TUTORIAL_UI.allTopics }}
                    </button>
                    <button
                        v-for="topic in TUTORIAL_TOPICS"
                        :key="topic.id"
                        type="button"
                        :aria-pressed="selectedTopic === topic.id"
                        class="tutorial-topic"
                        :class="
                            selectedTopic === topic.id ? 'bg-accent text-accent-contrast' : 'bg-surface text-ink-muted'
                        "
                        @click="selectTopic(topic.id)"
                    >
                        {{ topic.title }}
                    </button>
                </nav>
                <div
                    ref="guideBodyRef"
                    class="min-h-0 flex-1 overflow-y-auto overscroll-contain p-3 sm:p-5 space-y-6"
                    aria-live="polite"
                >
                    <p v-if="!filteredTopics.length" role="status" class="text-sm text-ink-subtle py-4">
                        {{ TUTORIAL_UI.noResults }}
                    </p>
                    <section
                        v-for="topic in filteredTopics"
                        :key="topic.id"
                        class="space-y-3"
                        :aria-labelledby="'tutorial-topic-' + topic.id"
                    >
                        <div>
                            <h3 :id="'tutorial-topic-' + topic.id" class="font-bold text-base">{{ topic.title }}</h3>
                            <p class="text-sm text-ink-muted mt-1 leading-relaxed">{{ topic.summary }}</p>
                        </div>
                        <article
                            v-for="(card, index) in topic.cards"
                            :key="index"
                            class="p-3 bg-surface border border-line space-y-2"
                        >
                            <h4 class="text-sm font-bold">{{ card.title }}</h4>
                            <p
                                v-for="(paragraph, paragraphIndex) in card.body"
                                :key="paragraphIndex"
                                class="text-sm text-ink-muted leading-relaxed break-words"
                            >
                                {{ paragraph }}
                            </p>
                        </article>
                    </section>
                </div>
                <footer class="shrink-0 flex flex-wrap justify-between gap-2 border-t border-line p-3 sm:p-4">
                    <button type="button" class="tutorial-button border-line" @click="closeHelp">
                        {{ TUTORIAL_UI.close }}
                    </button>
                    <button
                        type="button"
                        class="tutorial-button border-accent bg-accent text-accent-contrast hover:bg-accent-hover"
                        @click="restart"
                    >
                        {{ TUTORIAL_UI.startTour }}
                    </button>
                </footer>
            </template>
        </section>
    </div>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue';
import { X } from 'lucide-vue-next';
import { STORAGE_KEYS } from '../constants/storage';
import { TUTORIAL_UI, TUTORIAL_STEPS, TUTORIAL_TOPICS } from '../constants/tutorial';

const isActive = ref(false);
const view = ref('tour');
const currentStepIndex = ref(0);
const query = ref('');
const selectedTopic = ref('');
const dialogRef = ref(null);
const guideBodyRef = ref(null);
const tourBodyRef = ref(null);
const targetRect = ref(null);
const mobile = ref(false);
const tourStyle = ref({});
let returnFocus = null;
let startupTimer;
let positionFrame;
let disposed = false;
let openingVersion = 0;

const currentStep = computed(() => TUTORIAL_STEPS[currentStepIndex.value]);
const currentBody = computed(() =>
    mobile.value && currentStep.value?.mobileBody ? currentStep.value.mobileBody : currentStep.value?.body || []
);
const highlightStyle = computed(() => ({
    top: `${targetRect.value.top}px`,
    left: `${targetRect.value.left}px`,
    width: `${targetRect.value.width}px`,
    height: `${targetRect.value.height}px`,
}));
const filteredTopics = computed(() => {
    const keyword = query.value.trim().toLocaleLowerCase();
    return TUTORIAL_TOPICS.filter((topic) => !selectedTopic.value || topic.id === selectedTopic.value).flatMap(
        (topic) => {
            if (!keyword) return [topic];
            const matches = (text) => text.toLocaleLowerCase().includes(keyword);
            if (matches(topic.title) || matches(topic.summary)) return [topic];
            const cards = topic.cards.filter((card) => matches(card.title) || card.body.some(matches));
            return cards.length ? [{ ...topic, cards }] : [];
        }
    );
});

const markDone = () => {
    try {
        localStorage.setItem(STORAGE_KEYS.TUTORIAL_DONE, 'true');
    } catch {
        /* Private browsing may reject preference writes. */
    }
};
const visibleRect = (element) => {
    if (!element || !element.getClientRects().length) return null;
    const rect = element.getBoundingClientRect();
    if (!rect.width || !rect.height || getComputedStyle(element).visibility === 'hidden') return null;
    const top = Math.max(0, rect.top),
        left = Math.max(0, rect.left);
    const right = Math.min(window.innerWidth, rect.right),
        bottom = Math.min(window.innerHeight, rect.bottom);
    return right > left && bottom > top
        ? { top, left, right, bottom, width: right - left, height: bottom - top }
        : null;
};
const updatePosition = () => {
    if (!isActive.value || view.value !== 'tour' || !currentStep.value) return;
    const width = window.innerWidth,
        height = window.innerHeight;
    mobile.value = width < 768;
    const step = currentStep.value;
    let rect = visibleRect(step.targetId && document.getElementById(step.targetId));
    if (mobile.value && step.mobileTargetId) rect = visibleRect(document.getElementById(step.mobileTargetId)) || rect;
    targetRect.value = rect;
    const panelWidth = Math.min(420, width - 24);
    const panelHeight = Math.min(dialogRef.value?.offsetHeight || 380, height - 24);
    let top = (height - panelHeight) / 2;
    let left = (width - panelWidth) / 2;
    if (rect) {
        left = Math.max(12, Math.min(width - panelWidth - 12, rect.left + rect.width / 2 - panelWidth / 2));
        if (height - rect.bottom >= panelHeight + 24) top = rect.bottom + 12;
        else if (rect.top >= panelHeight + 24) top = rect.top - panelHeight - 12;
    }
    tourStyle.value = {
        width: `${panelWidth}px`,
        left: `${left}px`,
        top: `${Math.max(12, Math.min(height - panelHeight - 12, top))}px`,
    };
};
const schedulePosition = () => {
    cancelAnimationFrame(positionFrame);
    positionFrame = requestAnimationFrame(updatePosition);
};
const focusDialog = async () => {
    const version = ++openingVersion;
    await nextTick();
    if (disposed || !isActive.value || version !== openingVersion) return;
    dialogRef.value?.focus({ preventScroll: true });
    schedulePosition();
};
const open = (nextView) => {
    clearTimeout(startupTimer);
    if (!isActive.value) returnFocus = document.activeElement;
    view.value = nextView;
    targetRect.value = null;
    isActive.value = true;
    focusDialog();
};
const showGuide = () => {
    if (view.value === 'tour') markDone();
    open('guide');
};
const openGuide = () => {
    query.value = '';
    selectedTopic.value = '';
    showGuide();
};
const restart = () => {
    currentStepIndex.value = 0;
    open('tour');
};
const closeHelp = () => {
    if (!isActive.value) return;
    if (view.value === 'tour') markDone();
    isActive.value = false;
    targetRect.value = null;
    openingVersion++;
    cancelAnimationFrame(positionFrame);
    if (returnFocus?.isConnected) returnFocus.focus({ preventScroll: true });
    returnFocus = null;
};
const finishTutorial = () => {
    markDone();
    closeHelp();
};
const nextStep = () => {
    if (currentStepIndex.value < TUTORIAL_STEPS.length - 1) currentStepIndex.value++;
    else finishTutorial();
};
const previousStep = () => {
    if (currentStepIndex.value > 0) currentStepIndex.value--;
};
const selectTopic = (id) => {
    selectedTopic.value = id;
    guideBodyRef.value?.scrollTo({ top: 0 });
};
const onKeydown = (event) => {
    if (!isActive.value) return;
    if (event.key === 'Escape') {
        event.preventDefault();
        event.stopImmediatePropagation();
        closeHelp();
    } else if (event.key === 'Tab') {
        const nodes = [
            ...dialogRef.value.querySelectorAll(
                'button:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex="0"]'
            ),
        ].filter((node) => node.getClientRects().length);
        const first = nodes[0],
            last = nodes[nodes.length - 1];
        if (!first) {
            event.preventDefault();
            dialogRef.value.focus();
        } else if (event.shiftKey && (document.activeElement === first || !nodes.includes(document.activeElement))) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && (document.activeElement === last || !nodes.includes(document.activeElement))) {
            event.preventDefault();
            first.focus();
        }
        event.stopImmediatePropagation();
    }
};
const onFocusin = (event) => {
    if (isActive.value && dialogRef.value && !dialogRef.value.contains(event.target))
        dialogRef.value.focus({ preventScroll: true });
};
watch(currentStepIndex, async () => {
    await nextTick();
    if (disposed) return;
    tourBodyRef.value?.scrollTo({ top: 0 });
    schedulePosition();
});
watch(query, () => {
    selectedTopic.value = '';
    guideBodyRef.value?.scrollTo({ top: 0 });
});
watch(selectedTopic, () => guideBodyRef.value?.scrollTo({ top: 0 }));
onMounted(() => {
    window.addEventListener('keydown', onKeydown, true);
    window.addEventListener('focusin', onFocusin, true);
    window.addEventListener('resize', schedulePosition);
    window.addEventListener('scroll', schedulePosition, true);
    let done = false;
    try {
        done = !!localStorage.getItem(STORAGE_KEYS.TUTORIAL_DONE);
    } catch {
        /* The tour still works without persistent preferences. */
    }
    if (!done) startupTimer = setTimeout(restart, 1000);
});
onUnmounted(() => {
    disposed = true;
    clearTimeout(startupTimer);
    cancelAnimationFrame(positionFrame);
    window.removeEventListener('keydown', onKeydown, true);
    window.removeEventListener('focusin', onFocusin, true);
    window.removeEventListener('resize', schedulePosition);
    window.removeEventListener('scroll', schedulePosition, true);
});

defineExpose({ openGuide, restart });
</script>

<style scoped>
.tutorial-dialog {
    max-height: calc(100dvh - 24px);
}
.tutorial-compact-topics {
    display: none;
}
@media (max-width: 639px), (max-height: 420px) {
    .tutorial-compact-topics {
        display: block;
    }
    .tutorial-wide-topics {
        display: none;
    }
}
.tutorial-guide {
    inset: 12px;
    width: min(960px, calc(100% - 24px));
    margin-inline: auto;
}
.tutorial-button {
    min-height: 40px;
    padding: 0.5rem 0.8rem;
    border-width: 1px;
    font-size: 0.75rem;
    font-weight: 700;
}
.tutorial-topic {
    min-height: 36px;
    padding: 0.35rem 0.6rem;
    border: 1px solid rgb(var(--mp-line));
    font-size: 0.75rem;
    text-align: left;
}
@media (max-height: 420px) {
    #tutorial-subtitle {
        display: none;
    }
    .tutorial-guide label[for='tutorial-guide-search'] {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip-path: inset(50%);
    }
    .tutorial-guide input {
        padding: 0.35rem 0.5rem;
    }
    .tutorial-guide > div.shrink-0 {
        padding: 0.5rem 0.75rem;
    }
    .tutorial-dialog .tutorial-button {
        min-height: 32px;
        padding: 0.35rem 0.6rem;
    }
    .tutorial-dialog header {
        padding: 0.5rem 0.75rem;
    }
    .tutorial-dialog footer {
        padding: 0.5rem 0.75rem;
    }
    .tutorial-dialog nav {
        max-height: 20dvh;
    }
}
</style>
