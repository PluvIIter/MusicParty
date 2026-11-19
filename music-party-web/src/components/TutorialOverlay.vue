<template>
    <div
        v-if="isActive"
        class="tutorial-layer fixed inset-0 z-[9999]"
        :data-tutorial-step="currentStep?.id"
        @pointerdown.self.prevent
    >
        <!-- 引导模式或没有高亮目标：整屏蒙层 -->
        <div
            v-if="view !== 'tour' || !targetRect"
            class="tutorial-shade pointer-events-none fixed inset-0 bg-brand/70 backdrop-blur-sm"
            aria-hidden="true"
        ></div>
        <!-- 巡览模式：用四块蒙层围出高亮区域，目标本身保持清晰 -->
        <template v-else>
            <div
                class="tutorial-shade pointer-events-none fixed bg-brand/70 backdrop-blur-sm"
                :style="shadeStyles.top"
                aria-hidden="true"
            ></div>
            <div
                class="tutorial-shade pointer-events-none fixed bg-brand/70 backdrop-blur-sm"
                :style="shadeStyles.bottom"
                aria-hidden="true"
            ></div>
            <div
                class="tutorial-shade pointer-events-none fixed bg-brand/70 backdrop-blur-sm"
                :style="shadeStyles.left"
                aria-hidden="true"
            ></div>
            <div
                class="tutorial-shade pointer-events-none fixed bg-brand/70 backdrop-blur-sm"
                :style="shadeStyles.right"
                aria-hidden="true"
            ></div>
            <div
                class="fixed pointer-events-none border-2 border-accent rounded-sm shadow-[0_0_20px_rgb(var(--mp-accent)/0.35)]"
                :style="highlightStyle"
                aria-hidden="true"
            ></div>
        </template>
        <section
            ref="dialogRef"
            role="dialog"
            aria-modal="true"
            aria-labelledby="tutorial-title"
            aria-describedby="tutorial-subtitle"
            tabindex="-1"
            class="tutorial-dialog fixed flex min-h-0 flex-col bg-surface-raised border border-line shadow-2xl text-ink outline-none"
            :class="view === 'guide' ? 'tutorial-guide' : ['tutorial-tour', { 'tutorial-tight': tightPanel }]"
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
                        {{ TUTORIAL_UI.stepLabel }} {{ currentStepIndex + 1 }} / {{ availableSteps.length }}
                    </p>
                    <h3 class="font-bold text-base">
                        {{ mobile && currentStep.mobileTitle ? currentStep.mobileTitle : currentStep.title }}
                    </h3>
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
                            {{ currentStepIndex === availableSteps.length - 1 ? TUTORIAL_UI.finish : TUTORIAL_UI.next }}
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
import { availableTutorialSteps, tutorialStepAfterChange, tutorialTarget } from '../domain/tutorialTargets';
import { unlockPreview } from '../domain/tutorialPreview';
import { useSourcesStore } from '../stores/sources';

const isActive = ref(false);
const view = ref('tour');
const currentStepId = ref('welcome');
const availableSteps = ref([TUTORIAL_STEPS[0]]);
const sources = useSourcesStore();
const query = ref('');
const selectedTopic = ref('');
const dialogRef = ref(null);
const guideBodyRef = ref(null);
const tourBodyRef = ref(null);
const targetRect = ref(null);
const mobile = ref(false);
const tourStyle = ref({});
const tightPanel = ref(false);
let returnFocus = null;
let startupTimer;
let positionFrame;
let disposed = false;
let openingVersion = 0;
let targetObserver;

const currentStepIndex = computed(() => availableSteps.value.findIndex((step) => step.id === currentStepId.value));
const currentStep = computed(() => availableSteps.value[currentStepIndex.value]);
const currentBody = computed(() =>
    mobile.value && currentStep.value?.mobileBody ? currentStep.value.mobileBody : currentStep.value?.body || []
);
const highlightStyle = computed(() => ({
    top: `${targetRect.value.top}px`,
    left: `${targetRect.value.left}px`,
    width: `${targetRect.value.width}px`,
    height: `${targetRect.value.height}px`,
}));
// 四块蒙层围出目标区域：目标本身不被压暗或模糊。
const shadeStyles = computed(() => {
    const rect = targetRect.value || { top: 0, left: 0, width: 0, height: 0 };
    const top = Math.max(0, rect.top);
    const left = Math.max(0, rect.left);
    const width = Math.max(0, rect.width);
    const height = Math.max(0, rect.height);
    return {
        top: { top: '0', left: '0', right: '0', height: `${top}px` },
        bottom: { top: `${top + height}px`, left: '0', right: '0', bottom: '0' },
        left: { top: `${top}px`, left: '0', width: `${left}px`, height: `${height}px` },
        right: { top: `${top}px`, left: `${left + width}px`, right: '0', height: `${height}px` },
    };
});
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
    const nextSteps = availableTutorialSteps(TUTORIAL_STEPS, mobile.value, (capability) =>
        sources.enabled.some((source) => sources.supports(source.id, capability))
    );
    // 「允许本机播放声音」这一步的按钮平时不显示，教程进入该步时会临时展示，所以固定保留这一步。
    const soundStep = TUTORIAL_STEPS.find((step) => step.id === 'sound');
    if (soundStep && !nextSteps.some((step) => step.id === 'sound')) nextSteps.push(soundStep);
    const nextId = tutorialStepAfterChange(availableSteps.value, nextSteps, currentStepId.value);
    if (availableSteps.value.map((step) => step.id).join() !== nextSteps.map((step) => step.id).join())
        availableSteps.value = nextSteps;
    currentStepId.value = nextId;
    const element = currentStep.value && tutorialTarget(currentStep.value, mobile.value);
    const rect = visibleRect(element);
    targetRect.value = rect;
    const panelWidth = Math.min(420, width - 24);
    let panelHeight = Math.min(380, height - 24);
    let maxHeight = height - 24;
    const gap = 12;
    tightPanel.value = false;
    let top = (height - panelHeight) / 2;
    let left = (width - panelWidth) / 2;
    if (rect) {
        left = Math.max(gap, Math.min(width - panelWidth - gap, rect.left + rect.width / 2 - panelWidth / 2));
        const spaceBelow = height - rect.bottom;
        const spaceAbove = rect.top;
        const spaceRight = width - rect.right;
        const spaceLeft = rect.left;
        if (spaceBelow >= panelHeight + gap * 2) {
            top = rect.bottom + gap;
        } else if (spaceAbove >= panelHeight + gap * 2) {
            top = rect.top - panelHeight - gap;
        } else if (spaceRight >= panelWidth + gap * 2 || spaceLeft >= panelWidth + gap * 2) {
            // 上下都放不下：改放左右，避免弹窗把高亮目标整个盖住。
            left = spaceRight >= panelWidth + gap * 2 ? rect.right + gap : rect.left - panelWidth - gap;
            top = Math.max(gap, Math.min(height - panelHeight - gap, rect.top));
        } else {
            maxHeight = Math.max(spaceAbove, spaceBelow) - gap * 2;
            panelHeight = Math.min(panelHeight, maxHeight);
            top = spaceBelow >= spaceAbove ? rect.bottom + gap : rect.top - panelHeight - gap;
            tightPanel.value = true;
        }
    }
    tourStyle.value = {
        height: `${panelHeight}px`,
        maxHeight: `${maxHeight}px`,
        width: `${panelWidth}px`,
        left: `${left}px`,
        top: `${Math.max(gap, Math.min(height - panelHeight - gap, top))}px`,
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
    syncUnlockPreview();
    updatePosition();
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
    currentStepId.value = 'welcome';
    open('tour');
};
const closeHelp = () => {
    if (!isActive.value) return;
    unlockPreview.value = false;
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
    updatePosition();
    if (currentStepIndex.value < availableSteps.value.length - 1)
        currentStepId.value = availableSteps.value[currentStepIndex.value + 1].id;
    else finishTutorial();
};
const previousStep = () => {
    updatePosition();
    if (currentStepIndex.value > 0) currentStepId.value = availableSteps.value[currentStepIndex.value - 1].id;
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
/** 最后一步要指「点此开启声音」：该按钮平时不显示，进入这一步时临时展示。 */
const syncUnlockPreview = () => {
    unlockPreview.value = isActive.value && view.value === 'tour' && currentStepId.value === 'sound';
};
watch([currentStepId, view, isActive], async () => {
    syncUnlockPreview();
    await nextTick();
    if (disposed) return;
    tourBodyRef.value?.scrollTo({ top: 0 });
    schedulePosition();
});
watch(() => sources.enabled, schedulePosition, { deep: true });
watch(query, () => {
    selectedTopic.value = '';
    guideBodyRef.value?.scrollTo({ top: 0 });
});
watch(selectedTopic, () => guideBodyRef.value?.scrollTo({ top: 0 }));
onMounted(() => {
    targetObserver = new MutationObserver((records) => {
        if (!isActive.value || view.value !== 'tour') return;
        if (
            records.some(({ target, addedNodes, removedNodes }) => {
                if (target.closest?.('.tutorial-layer')) return false;
                return [target, ...addedNodes, ...removedNodes].some(
                    (node) => node.matches?.('[id^="tutorial-"]') || node.querySelector?.('[id^="tutorial-"]')
                );
            })
        )
            schedulePosition();
    });
    targetObserver.observe(document.body, {
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: ['disabled', 'aria-disabled', 'data-tutorial-available', 'style', 'class'],
    });
    window.addEventListener('keydown', onKeydown, true);
    window.addEventListener('focusin', onFocusin, true);
    window.addEventListener('resize', schedulePosition);
    window.addEventListener('scroll', schedulePosition, true);
    window.addEventListener('transitionend', schedulePosition, true);
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
    targetObserver?.disconnect();
    clearTimeout(startupTimer);
    cancelAnimationFrame(positionFrame);
    window.removeEventListener('keydown', onKeydown, true);
    window.removeEventListener('focusin', onFocusin, true);
    window.removeEventListener('resize', schedulePosition);
    window.removeEventListener('scroll', schedulePosition, true);
    window.removeEventListener('transitionend', schedulePosition, true);
});

defineExpose({ openGuide, restart });
</script>

<style scoped>
.tutorial-dialog {
    max-height: calc(100dvh - 24px);
}
.tutorial-tight header {
    padding: 0.5rem 0.75rem;
}
.tutorial-tight #tutorial-subtitle,
.tutorial-tight header p,
.tutorial-tight footer > div:first-child {
    display: none;
}
.tutorial-tight footer {
    padding: 0.5rem 0.75rem;
}
.tutorial-tight .tutorial-button {
    min-height: 32px;
    padding: 0.35rem 0.6rem;
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
