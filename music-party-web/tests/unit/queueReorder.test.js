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
    const target = (id) => {
        const row = {
            dataset: { queueId: id },
            getBoundingClientRect: () => ({ top: 0, height: 100 }),
            closest: () => null,
        };
        Object.defineProperty(document, 'elementFromPoint', {
            configurable: true,
            value: () => ({ closest: () => row }),
        });
    };
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
        reorder.start(player.queue[3], pointer());
        target('a');
        reorder.move(pointer(30));
        expect(handle.setPointerCapture).toHaveBeenCalledWith(1);
        expect(player.reorderSong).not.toHaveBeenCalled();
        reorder.finish(pointer(30));
        expect(player.reorderSong).toHaveBeenCalledExactlyOnceWith('c', 'a');
        expect(player.queue.map(({ queueId }) => queueId)).toEqual(['a', 'other', 'b', 'c']);
    });
    it('drops after the last own song with a null target', () => {
        reorder.start(player.queue[0], pointer());
        target('c');
        reorder.move(pointer(80));
        reorder.finish(pointer(80));
        expect(player.reorderSong).toHaveBeenCalledExactlyOnceWith('a', null);
    });
    it('ignores a different pointer and invalid/other-member targets', () => {
        reorder.start(player.queue[0], pointer());
        target('other');
        reorder.move(pointer(30));
        reorder.finish(pointer(30, 2));
        expect(reorder.drag.value).not.toBeNull();
        reorder.finish(pointer(30));
        expect(player.reorderSong).not.toHaveBeenCalled();
    });
    it.each(['queue replacement', 'in-place queue change', 'disconnect', 'guest', 'mode', 'escape', 'pointercancel'])(
        'cancels stale drag on %s',
        (change) => {
            reorder.start(player.queue[3], pointer());
            target('a');
            reorder.move(pointer(30));
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
        reorder.start(player.queue[0], pointer());
        target('other');
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

    it('arrow keys move among own regular songs, skipping all other entries', () => {
        reorder.keyboard(player.queue[2], { key: 'ArrowUp', preventDefault: vi.fn() });
        reorder.keyboard(player.queue[0], { key: 'ArrowDown', preventDefault: vi.fn() });
        reorder.keyboard(player.queue[2], { key: 'ArrowDown', preventDefault: vi.fn() });
        expect(player.reorderSong.mock.calls).toEqual([
            ['b', 'a'],
            ['a', 'c'],
            ['b', null],
        ]);
    });
    it('does not start for pinned/FM/other items or disconnected guests', () => {
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
