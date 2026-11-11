import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, reactive } from 'vue';
import { useQueueReorder } from '../../src/composables/useQueueReorder';

const item = (queueId, token = 'mine', priority = 'REGULAR', platform = 'netease') => ({
    queueId,
    priority,
    enqueuedBy: { token },
    music: { platform },
});

describe('pointer and keyboard queue reorder', () => {
    let player, user, reorder, app, handle;
    const pointer = (clientY = 10, pointerId = 1) => ({
        clientY,
        clientX: 10,
        pointerId,
        currentTarget: handle,
        preventDefault: vi.fn(),
        button: 0,
    });
    // 插入点吸附到卡片之间的空隙：用真实 DOM 行模拟一列 100px 高、间距 8px 的卡片
    const mountRows = (ids) => {
        document.body.innerHTML = '';
        ids.forEach((id, index) => {
            const row = document.createElement('div');
            row.dataset.queueId = id;
            const top = 100 + index * 108;
            row.getBoundingClientRect = () => ({ top, height: 100, bottom: top + 100 });
            document.body.appendChild(row);
        });
    };
    const gapY = (index) => 96 + index * 108;
    const tailY = (count) => 100 + (count - 1) * 108 + 104;
    beforeEach(() => {
        player = reactive({
            queue: [item('a'), item('other', 'other'), item('b'), item('c')],
            connected: true,
            playMode: 'SEQUENTIAL',
            reorderSong: vi.fn(),
        });
        user = reactive({ userToken: 'mine', isGuest: false });
        handle = { setPointerCapture: vi.fn(), hasPointerCapture: () => false };
        app = createApp({
            setup() {
                reorder = useQueueReorder(player, user);
                return () => null;
            },
        });
        app.mount(document.createElement('div'));
    });
    afterEach(() => app.unmount());
    it('uses pointer capture, waits for release and never mutates the queue locally', () => {
        mountRows(['a', 'other', 'b', 'c']);
        reorder.start(player.queue[3], pointer());
        reorder.move(pointer(gapY(0)));
        expect(handle.setPointerCapture).toHaveBeenCalledWith(1);
        expect(player.reorderSong).not.toHaveBeenCalled();
        reorder.finish(pointer(gapY(0)));
        expect(player.reorderSong).toHaveBeenCalledExactlyOnceWith('c', 'a');
        expect(player.queue.map(({ queueId }) => queueId)).toEqual(['a', 'other', 'b', 'c']);
    });
    it('moves another member song before an owned target in sequential mode', () => {
        mountRows(['a', 'other', 'b', 'c']);
        reorder.start(player.queue[1], pointer());
        reorder.finish(pointer(gapY(0)));
        expect(player.reorderSong).toHaveBeenCalledExactlyOnceWith('other', 'a');
    });
    it('uses another member row as a sequential pointer target', () => {
        mountRows(['a', 'other', 'b', 'c']);
        reorder.start(player.queue[3], pointer());
        reorder.finish(pointer(gapY(1)));
        expect(player.reorderSong).toHaveBeenCalledExactlyOnceWith('c', 'other');
    });
    it('arrow keys navigate all member regular songs in sequential mode', () => {
        reorder.keyboard(player.queue[2], { key: 'ArrowUp', preventDefault: vi.fn() });
        reorder.keyboard(player.queue[1], { key: 'ArrowDown', preventDefault: vi.fn() });
        expect(player.reorderSong.mock.calls).toEqual([
            ['b', 'other'],
            ['other', 'c'],
        ]);
    });
    it('drops after the last eligible song with a null target', () => {
        mountRows(['a', 'other', 'b', 'c']);
        reorder.start(player.queue[0], pointer());
        reorder.move(pointer(tailY(4)));
        reorder.finish(pointer(tailY(4)));
        expect(player.reorderSong).toHaveBeenCalledExactlyOnceWith('a', null);
    });
    it('ignores a different pointer and other-member targets in round-robin', () => {
        player.playMode = 'ROUND_ROBIN';
        mountRows(['a', 'other', 'b', 'c']);
        reorder.start(player.queue[0], pointer());
        reorder.move(pointer(gapY(1)));
        reorder.finish(pointer(gapY(1), 2));
        expect(reorder.drag.value).not.toBeNull();
        reorder.finish(pointer(gapY(1)));
        expect(player.reorderSong).not.toHaveBeenCalled();
    });
    it('cancels a cross-member drag before releasing in a different mode', () => {
        mountRows(['a', 'other', 'b', 'c']);
        reorder.start(player.queue[1], pointer());
        reorder.move(pointer(gapY(0)));
        expect(reorder.drag.value.hasTarget).toBe(true);
        player.playMode = 'ROUND_ROBIN';
        expect(reorder.drag.value).toBeNull();
        reorder.finish(pointer(30));
        expect(player.reorderSong).not.toHaveBeenCalled();
    });
    it.each(['queue replacement', 'in-place queue change', 'disconnect', 'guest', 'mode', 'escape', 'pointercancel'])(
        'cancels stale drag on %s',
        (change) => {
            mountRows(['a', 'other', 'b', 'c']);
            reorder.start(player.queue[3], pointer());
            reorder.move(pointer(gapY(0)));
            if (change === 'queue replacement') player.queue = [...player.queue];
            if (change === 'in-place queue change') player.queue[0].priority = 'USER_TOP';
            if (change === 'disconnect') player.connected = false;
            if (change === 'guest') user.isGuest = true;
            if (change === 'mode') player.playMode = 'ROUND_ROBIN';
            if (change === 'escape') document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
            if (change === 'pointercancel') reorder.cancel();
            expect(reorder.drag.value).toBeNull();
            reorder.finish(pointer(30));
            expect(player.reorderSong).not.toHaveBeenCalled();
        }
    );
    it('continuously scrolls past other-member rows using the stable queue container', () => {
        let frame;
        vi.stubGlobal(
            'requestAnimationFrame',
            vi.fn((callback) => {
                frame = callback;
                return 9;
            })
        );
        vi.stubGlobal('cancelAnimationFrame', vi.fn());
        const container = {
            getBoundingClientRect: () => ({ top: 0, bottom: 100, left: 0, right: 100 }),
            scrollBy: vi.fn(),
            setPointerCapture: vi.fn(),
            hasPointerCapture: () => false,
        };
        handle.closest = () => container;
        mountRows(['a', 'other', 'b', 'c']);
        reorder.start(player.queue[0], pointer());
        reorder.move(pointer(90));
        frame();
        frame();
        expect(container.scrollBy).toHaveBeenCalledTimes(2);
        expect(container.setPointerCapture).toHaveBeenCalledWith(1);
        expect(player.reorderSong).not.toHaveBeenCalled();
        reorder.cancel();
        expect(cancelAnimationFrame).toHaveBeenCalledWith(9);
        vi.unstubAllGlobals();
    });

    it.each(['ROUND_ROBIN', 'SHUFFLE', 'REPEAT_ONE'])('arrow keys keep personal targets in %s', (mode) => {
        player.playMode = mode;
        reorder.keyboard(player.queue[2], { key: 'ArrowUp', preventDefault: vi.fn() });
        reorder.keyboard(player.queue[0], { key: 'ArrowDown', preventDefault: vi.fn() });
        reorder.keyboard(player.queue[2], { key: 'ArrowDown', preventDefault: vi.fn() });
        expect(player.reorderSong.mock.calls).toEqual([
            ['b', 'a'],
            ['a', 'c'],
            ['b', null],
        ]);
    });
    it('does not start for pinned/FM/other items in round-robin or disconnected guests', () => {
        player.playMode = 'ROUND_ROBIN';
        for (const entry of [
            item('p', 'mine', 'GLOBAL_TOP'),
            item('f', 'mine', 'REGULAR', 'netease-fm'),
            item('o', 'other'),
        ])
            reorder.start(entry, pointer());
        player.connected = false;
        reorder.start(player.queue[0], pointer());
        expect(reorder.drag.value).toBeNull();
    });
});
