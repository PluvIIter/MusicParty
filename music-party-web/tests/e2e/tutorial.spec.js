import { test, expect } from '@playwright/test';
import { compactFrames } from './compact-fixture';
import { TUTORIAL_UI, TUTORIAL_STEPS, TUTORIAL_TOPICS } from '../../src/constants/tutorial';

async function fixture(page, { done = true, denyStorage = false } = {}) {
    const sent = [];
    await page.addInitScript(
        ({ done, denyStorage }) => {
            localStorage.setItem('mp_username', 'TutorialTester');
            localStorage.setItem('mp_user_token', 'tutorial-member');
            localStorage.setItem('mp_pwa_hint_shown', 'true');
            localStorage.setItem('mp_auto_lite_mode', 'false');
            if (done) localStorage.setItem('mp_tutorial_done_v1', 'true');
            else localStorage.removeItem('mp_tutorial_done_v1');
            if (denyStorage) {
                const get = Storage.prototype.getItem;
                const set = Storage.prototype.setItem;
                Storage.prototype.getItem = function (key) {
                    if (key === 'mp_tutorial_done_v1') throw new DOMException('blocked', 'SecurityError');
                    return get.call(this, key);
                };
                Storage.prototype.setItem = function (key, value) {
                    if (key === 'mp_tutorial_done_v1') throw new DOMException('blocked', 'SecurityError');
                    return set.call(this, key, value);
                };
            }
        },
        { done, denyStorage }
    );
    await page.route('**/api/**', (route) => {
        const path = new URL(route.request().url()).pathname;
        const response =
            path === '/api/auth/status'
                ? { isSetup: true, hasProtection: false }
                : path === '/api/config'
                  ? { authorName: 'Fixture', backWords: 'MUSIC PARTY' }
                  : path === '/api/v2/sources'
                    ? { schemaVersion: 2, sources: [] }
                    : {};
        return route.fulfill({ json: response });
    });
    await page.routeWebSocket('**/ws', (socket) => {
        const subscriptions = new Map();
        const snapshot = compactFrames();
        let sequence = 0;
        const send = (destination, body) => {
            for (const [id, topic] of subscriptions)
                if (topic === destination)
                    socket.send(
                        `MESSAGE\nsubscription:${id}\nmessage-id:${++sequence}\ndestination:${destination}\n\n${JSON.stringify(body)}\0`
                    );
        };
        socket.onMessage((raw) => {
            for (const frame of String(raw).split('\0')) {
                const [head, body] = frame.split('\n\n');
                const lines = head.trim().split('\n');
                const command = lines.shift();
                const headers = Object.fromEntries(
                    lines.map((line) => {
                        const split = line.indexOf(':');
                        return [line.slice(0, split), line.slice(split + 1)];
                    })
                );
                if (command === 'CONNECT')
                    socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\nsession:tutorial\n\n\0');
                if (command === 'SUBSCRIBE') {
                    subscriptions.set(headers.id, headers.destination);
                    if (headers.destination === '/app/user/me')
                        send(headers.destination, {
                            token: 'tutorial-member',
                            sessionId: 'tutorial',
                            name: 'TutorialTester',
                            isGuest: false,
                        });
                    if (headers.destination === '/app/chat/history') send(headers.destination, []);
                }
                if (command === 'SEND') {
                    sent.push({ destination: headers.destination, body });
                    if (headers.destination === '/app/player/compact-resync')
                        send(
                            '/topic/player/compact',
                            snapshot({
                                queue: [],
                                onlineUsers: [],
                                nowPlaying: null,
                                isPaused: true,
                                playMode: 'SEQUENTIAL',
                                config: {},
                            })
                        );
                }
            }
        });
    });
    await page.goto('/', { waitUntil: 'commit' });
    await page.getByRole('button', { name: 'CONNECT', exact: true }).click();
    return sent;
}

const guide = (page) => page.getByRole('dialog', { name: TUTORIAL_UI.title, exact: true });
async function expectInsideViewport(page) {
    const box = await guide(page).boundingBox();
    const viewport = page.viewportSize();
    expect(box.x).toBeGreaterThanOrEqual(0);
    expect(box.y).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width).toBeLessThanOrEqual(viewport.width + 1);
    expect(box.y + box.height).toBeLessThanOrEqual(viewport.height + 1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
}

test('first visit walks all eight steps even if highlights are unavailable, without controlling the room', async ({
    page,
}) => {
    const sent = await fixture(page, { done: false });
    await page.addStyleTag({
        content: '[id^="tutorial-"]:not(#tutorial-title):not(#tutorial-subtitle) { visibility: hidden !important; }',
    });
    const dialog = guide(page);
    await expect(dialog).toBeVisible();
    for (const [index, step] of TUTORIAL_STEPS.entries()) {
        await expect(dialog.getByRole('heading', { name: step.title, exact: true })).toBeVisible();
        await expect(dialog.getByText(`${TUTORIAL_UI.stepLabel} ${index + 1} / 8`, { exact: true })).toBeVisible();
        await expectInsideViewport(page);
        await dialog
            .getByRole('button', { name: index === 7 ? TUTORIAL_UI.finish : TUTORIAL_UI.next, exact: true })
            .click();
    }
    await expect(dialog).toHaveCount(0);
    expect(await page.evaluate(() => localStorage.getItem('mp_tutorial_done_v1'))).toBe('true');
    expect(sent.some(({ destination }) => /\/app\/(control|queue|user\/rename)/.test(destination))).toBe(false);
});

test('previous, skip and reopen preserve focus and keep the old completion marker valid', async ({ page }) => {
    await fixture(page);
    const trigger = page.getByRole('button', { name: TUTORIAL_UI.helpButton, exact: true });
    await trigger.click();
    await guide(page).getByRole('button', { name: TUTORIAL_UI.startTour, exact: true }).click();
    await guide(page).getByRole('button', { name: TUTORIAL_UI.next, exact: true }).click();
    await guide(page).getByRole('button', { name: TUTORIAL_UI.previous, exact: true }).click();
    await expect(guide(page).getByRole('heading', { name: TUTORIAL_STEPS[0].title, exact: true })).toBeVisible();
    await guide(page).getByRole('button', { name: TUTORIAL_UI.skip, exact: true }).click();
    await expect(trigger).toBeFocused();
    await trigger.click();
    await expect(guide(page).getByRole('button', { name: TUTORIAL_UI.startTour, exact: true })).toBeVisible();
    await page.reload({ waitUntil: 'commit' });
    await page.getByRole('button', { name: 'CONNECT', exact: true }).click();
    await expect(page.getByRole('button', { name: TUTORIAL_UI.helpButton, exact: true })).toBeVisible();
    await page.waitForTimeout(1200);
    await expect(guide(page)).toHaveCount(0);
});

test('complete guide filters topics, searches all text and reports no matches', async ({ page }) => {
    await fixture(page);
    await page.getByRole('button', { name: TUTORIAL_UI.helpButton, exact: true }).click();
    const dialog = guide(page);
    for (const topic of TUTORIAL_TOPICS) {
        await dialog.getByRole('button', { name: topic.title, exact: true }).click();
        await expect(dialog.getByRole('heading', { name: topic.title, exact: true })).toBeVisible();
    }
    await dialog.getByRole('searchbox', { name: TUTORIAL_UI.searchLabel }).fill('认领');
    await expect(dialog.getByRole('heading', { name: '认领离线成员歌单', exact: true })).toBeVisible();
    await expect(dialog.getByText(/并把你的昵称改为原歌单昵称/)).toBeVisible();
    await dialog.getByRole('searchbox', { name: TUTORIAL_UI.searchLabel }).fill('not-a-topic-xyz');
    await expect(dialog.getByText(TUTORIAL_UI.noResults, { exact: true })).toBeVisible();
});

test('keyboard focus stays within help and Escape returns to its trigger without opening background search', async ({
    page,
}) => {
    await fixture(page);
    const trigger = page.getByRole('button', { name: TUTORIAL_UI.helpButton, exact: true });
    await trigger.click();
    const dialog = guide(page);
    await page.keyboard.press('Shift+Tab');
    await expect(dialog.getByRole('button', { name: TUTORIAL_UI.startTour, exact: true })).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(dialog.getByRole('button', { name: TUTORIAL_UI.closeHelp, exact: true })).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await expect(trigger).toBeFocused();
    await expect(page.getByRole('textbox', { name: '搜索用户', exact: true })).toHaveCount(0);
});

for (const viewport of [
    { width: 320, height: 568 },
    { width: 375, height: 667 },
    { width: 667, height: 320 },
])
    test(`help and tour remain usable at ${viewport.width}x${viewport.height}, including lite mode`, async ({
        page,
    }) => {
        await page.setViewportSize(viewport);
        await fixture(page);
        await page.getByRole('button', { name: TUTORIAL_UI.helpButton, exact: true }).click();
        await expectInsideViewport(page);
        await page.screenshot({ path: test.info().outputPath('tutorial-guide.png') });
        const dialog = guide(page);
        await dialog.getByRole('combobox', { name: TUTORIAL_UI.topicNavigation }).selectOption('problems');
        expect(await dialog.locator('[aria-live="polite"]').evaluate((el) => el.clientHeight)).toBeGreaterThan(60);
        const lastHeading = dialog.getByRole('heading', { name: '什么时候应该找管理员', exact: true });
        await lastHeading.scrollIntoViewIfNeeded();
        await expect(lastHeading).toBeInViewport();
        await dialog.getByRole('button', { name: TUTORIAL_UI.startTour, exact: true }).click();
        await page.screenshot({ path: test.info().outputPath('tutorial-tour.png') });
        for (let i = 0; i < TUTORIAL_STEPS.length; i++) {
            await expectInsideViewport(page);
            await dialog
                .getByRole('button', { name: i === 7 ? TUTORIAL_UI.finish : TUTORIAL_UI.next, exact: true })
                .click();
        }
        await page.getByTitle('精简模式').click();
        const liteHelp = page.getByRole('button', { name: TUTORIAL_UI.helpButton, exact: true });
        await liteHelp.click();
        await expect(guide(page)).toBeVisible();
        await guide(page).getByRole('button', { name: TUTORIAL_UI.close, exact: true }).click();
        await expect(liteHelp).toBeFocused();
        await expect(page.getByRole('button', { name: '退出精简模式' })).toBeVisible();
    });

test('storage refusal does not prevent first-visit guide, completion or reopening', async ({ page }) => {
    const errors = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await fixture(page, { done: false, denyStorage: true });
    await expect(guide(page)).toBeVisible();
    await guide(page).getByRole('button', { name: TUTORIAL_UI.skip, exact: true }).click();
    await page.getByRole('button', { name: TUTORIAL_UI.helpButton, exact: true }).click();
    await expect(guide(page)).toBeVisible();
    expect(errors).toEqual([]);
});
