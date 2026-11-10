import { computed, onUnmounted, ref, watch } from 'vue';
import { isValidReorder, reorderableItems } from '../domain/queueRules';

export function useQueueReorder(player, userStore) {
    const drag = ref(null);
    const announcement = ref('');
    const enabled = computed(() => player.connected && !userStore.isGuest);
    const items = computed(() => reorderableItems(player.queue, userStore.userToken, player.playMode));
    let scrollFrame;
    const autoScroll = () => {
        const current = drag.value;
        if (!current?.moved) return;
        const container = current.container;
        const rect = container?.getBoundingClientRect();
        if (rect && current.x >= rect.left && current.x <= rect.right) {
            const delta = current.y < rect.top + 48 ? -12 : current.y > rect.bottom - 48 ? 12 : 0;
            if (delta) {
                container.scrollBy({ top: delta });
                updateTarget(current);
            }
        }
        scrollFrame = requestAnimationFrame(autoScroll);
    };
    const cancel = () => {
        cancelAnimationFrame(scrollFrame);
        scrollFrame = undefined;
        const current = drag.value;
        drag.value = null;
        const capture = current?.container || current?.handle;
        if (capture?.hasPointerCapture?.(current.pointerId)) capture.releasePointerCapture(current.pointerId);
    };
    const start = (item, event) => {
        if (!enabled.value || !items.value.some(({ queueId }) => queueId === item.queueId)) return;
        if (event.button !== undefined && event.button !== 0) return;
        event.preventDefault();
        cancel();
        drag.value = {
            queueId: item.queueId,
            pointerId: event.pointerId,
            handle: event.currentTarget,
            container: event.currentTarget.closest?.('[data-queue-scroll]'),
            startY: event.clientY,
            moved: false,
            hasTarget: false,
            beforeQueueId: null,
        };
        try {
            (drag.value.container || event.currentTarget).setPointerCapture?.(event.pointerId);
        } catch {
            // Synthetic events and older touch browsers may not have an active pointer.
        }
    };
    const updateTarget = (current) => {
        const row = document.elementFromPoint(current.x, current.y)?.closest('[data-queue-id]');
        const targetIndex = items.value.findIndex(({ queueId }) => queueId === row?.dataset.queueId);
        current.hasTarget = false;
        if (targetIndex < 0 || row.dataset.queueId === current.queueId) return;
        const rect = row.getBoundingClientRect();
        const before = current.y < rect.top + rect.height / 2;
        current.beforeQueueId = before ? row.dataset.queueId : (items.value[targetIndex + 1]?.queueId ?? null);
        current.hasTarget = isValidReorder(
            player.queue,
            userStore.userToken,
            current.queueId,
            current.beforeQueueId,
            player.playMode
        );
    };
    const move = (event) => {
        const current = drag.value;
        if (!current || current.pointerId !== event.pointerId) return;
        if (Math.abs(event.clientY - current.startY) < 5 && !current.moved) return;
        current.x = event.clientX;
        current.y = event.clientY;
        if (!current.moved) {
            current.moved = true;
            scrollFrame = requestAnimationFrame(autoScroll);
        }
        updateTarget(current);
    };
    const finish = (event) => {
        const current = drag.value;
        if (!current || current.pointerId !== event.pointerId) return;
        move(event);
        const valid = enabled.value && current.moved && current.hasTarget;
        const { queueId, beforeQueueId } = current;
        cancel();
        if (valid) {
            player.reorderSong(queueId, beforeQueueId);
            announcement.value = '已请求调整歌曲顺序，等待队列同步';
        }
    };
    const keyboard = (item, event) => {
        if (event.key === 'Escape') return cancel();
        if (!enabled.value || !['ArrowUp', 'ArrowDown'].includes(event.key)) return;
        event.preventDefault();
        const index = items.value.findIndex(({ queueId }) => queueId === item.queueId);
        if (index < 0) return;
        if (event.key === 'ArrowUp' && index > 0) player.reorderSong(item.queueId, items.value[index - 1].queueId);
        else if (event.key === 'ArrowDown' && index < items.value.length - 1)
            player.reorderSong(item.queueId, items.value[index + 2]?.queueId ?? null);
        else return;
        announcement.value = '已请求调整歌曲顺序，等待队列同步';
    };
    const onEscape = (event) => {
        if (event.key === 'Escape') cancel();
    };
    watch([() => player.queue, () => player.playMode, enabled, () => userStore.userToken], cancel, {
        deep: true,
        flush: 'sync',
    });
    document.addEventListener('keydown', onEscape);
    document.addEventListener('pointermove', move);
    document.addEventListener('pointerup', finish);
    document.addEventListener('pointercancel', cancel);
    onUnmounted(() => {
        cancel();
        document.removeEventListener('keydown', onEscape);
        document.removeEventListener('pointermove', move);
        document.removeEventListener('pointerup', finish);
        document.removeEventListener('pointercancel', cancel);
    });
    return { drag, announcement, start, move, finish, cancel, keyboard };
}
