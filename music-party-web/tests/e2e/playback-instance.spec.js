import { test, expect } from '@playwright/test';
import { audioServer, room, media } from './room';

let audio;
test.beforeAll(async () => {
    audio = await audioServer();
});
test.afterAll(async () => {
    await audio.close();
});

const syncInstance = async (page, playbackId, position) => {
    await page.evaluate(
        ({ playbackId, position }) => {
            const store = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('player');
            store.syncState({
                nowPlaying: { ...JSON.parse(JSON.stringify(store.nowPlaying)), currentPosition: position },
                playbackId,
                seekNonce: 0,
                queue: [],
                onlineUsers: [],
                isPaused: false,
                isLoading: false,
                playMode: 'REPEAT_ONE',
                config: { ...store.config, allowSeek: true },
            });
        },
        { playbackId, position }
    );
};

test('same-source instance restarts ended media and rejects an old drag', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    const hub = await room(page, { url: audio.url + '/tone.wav' });
    await expect.poll(async () => (await media(page)).ready >= 2).toBe(true);
    await syncInstance(page, 'first', 10000);
    await expect(page.getByRole('slider')).toBeVisible();
    await page.evaluate(() => {
        window.seekFrames = [];
        const send = WebSocket.prototype.send;
        WebSocket.prototype.send = function (frame) {
            if (String(frame).includes('/app/control/seek')) window.seekFrames.push(String(frame));
            return send.call(this, frame);
        };
    });
    const bar = await page.getByRole('slider').boundingBox();
    await page.mouse.move(bar.x + bar.width * 0.8, bar.y + bar.height / 2);
    await page.mouse.down();
    await syncInstance(page, 'second', 0);
    await page.mouse.up();
    expect(await page.evaluate(() => window.seekFrames)).toEqual([]);
    await syncInstance(page, 'end-of-pass', 179500);
    await expect.poll(async () => (await media(page)).time).toBeGreaterThan(179);
    await expect.poll(() => page.locator('audio').evaluate((element) => element.ended)).toBe(true);
    await syncInstance(page, 'third', 0);
    await expect
        .poll(async () => {
            const state = await media(page);
            return !state.paused && state.time < 3 && state.decoded > 0;
        })
        .toBe(true);
    await page.getByRole('slider').click({ position: { x: bar.width * 0.5, y: bar.height / 2 } });
    const frames = await page.evaluate(() => window.seekFrames);
    expect(frames).toHaveLength(1);
    const payload = JSON.parse(frames[0].split('\n\n')[1].replace(/\0/g, ''));
    expect(payload.playbackId).toBe('third');
    expect(payload.positionMs).toBeGreaterThan(80000);
    expect(hub.errors).toEqual([]);
});
