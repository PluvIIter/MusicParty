import { test, expect } from '@playwright/test';

async function room(
    page,
    { mode = 'SEQUENTIAL', guest = false, locked = false, reject = false, admin = false, long = false } = {}
) {
    const member = { token: 'mine', sessionId: 'mine-session', name: 'Alice', isGuest: guest };
    const other = { token: 'other', sessionId: 'other-session', name: 'Bob', isGuest: false };
    let queue = [member, other, member, member, member, member].map((enqueuedBy, index) => ({
        queueId: `q${index}`,
        enqueuedBy,
        priority: index === 4 ? 'GLOBAL_TOP' : 'REGULAR',
        status: 'READY',
        music: {
            id: String(index),
            name: `Song ${index}`,
            artists: ['Fixture'],
            platform: index === 5 ? 'netease-fm' : 'netease',
            duration: 180000,
            coverUrl: '',
        },
    }));
    if (long) {
        queue = Array.from({ length: 90 }, (_, index) => ({
            ...queue[0],
            queueId: `long-${index}`,
            enqueuedBy: index === 0 || index === 89 ? member : other,
            music: { ...queue[0].music, id: `long-${index}`, name: `Long song ${index}` },
        }));
    }
    let preferences = {},
        broadcast = () => {},
        disconnect = () => {},
        sendEvent = () => {};
    const sent = [],
        adminActions = [],
        errors = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.addInitScript(() => {
        localStorage.setItem('mp_user_token', 'mine');
        localStorage.setItem('mp_username', 'Alice');
        localStorage.setItem('mp_tutorial_done_v1', 'true');
        localStorage.setItem('mp_pwa_hint_shown', 'true');
        localStorage.setItem('mp_auto_lite_mode', 'false');
    });
    await page.route('https://**', (route) => route.abort());
    await page.route('**/api/**', (route) => {
        const path = new URL(route.request().url()).pathname;
        let body = {};
        if (path === '/api/auth/status') body = { isSetup: true, hasProtection: false };
        if (path === '/api/config') body = { authorName: 'Fixture' };
        if (path === '/api/admin/verify') body = { message: 'VERIFIED' };
        if (path === '/api/admin/player/action') {
            const update = route.request().postDataJSON();
            adminActions.push(update);
            if (update.action === 'SET_PLAY_MODE') mode = update.playMode;
            broadcast();
            body = { message: '模式已更新' };
        }
        return route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
    });
    await page.routeWebSocket('**/ws', (socket) => {
        disconnect = () => socket.close({ code: 1000, reason: 'Fixture disconnect' });
        const subscriptions = new Map();
        let sequence = 0;
        const send = (destination, body) => {
            for (const [id, topic] of subscriptions)
                if (topic === destination)
                    socket.send(
                        `MESSAGE\nsubscription:${id}\nmessage-id:${++sequence}\ndestination:${destination}\n\n${JSON.stringify(body)}\0`
                    );
        };
        broadcast = () => {
            send('/topic/player/queue', queue);
            send('/topic/player/state', {
                nowPlaying: null,
                queue,
                playMode: mode,
                isPaused: true,
                isPlayModeLocked: locked,
                memberShufflePreferences: preferences,
                onlineUsers: [member, other],
                config: { neteaseEnabled: true, privateDj: { mode: 'OFF' } },
            });
        };
        sendEvent = (message) => send('/user/queue/events', { action: 'ERROR', type: 'ERROR', message });
        socket.onMessage((raw) => {
            for (const frame of String(raw).split('\0')) {
                const [head, json] = frame.split('\n\n');
                const lines = head.trim().split('\n');
                const command = lines.shift();
                const headers = Object.fromEntries(
                    lines.map((line) => {
                        const at = line.indexOf(':');
                        return [line.slice(0, at), line.slice(at + 1)];
                    })
                );
                if (command === 'CONNECT')
                    socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\nsession:mine-session\n\n\0');
                if (command === 'SUBSCRIBE') {
                    subscriptions.set(headers.id, headers.destination);
                    if (headers.destination === '/app/user/me') send(headers.destination, member);
                    if (headers.destination === '/app/chat/history') send(headers.destination, []);
                }
                if (command !== 'SEND') continue;
                const body = JSON.parse(json || '{}');
                sent.push({ destination: headers.destination, body });
                if (headers.destination === '/app/player/resync') {
                    broadcast();
                    if (admin) send('/topic/player/events', { action: 'ADMIN_TRIGGER', userId: 'mine', type: 'INFO' });
                }
                if (headers.destination === '/app/control/play-mode') {
                    mode = body.playMode;
                    broadcast();
                }
                if (headers.destination === '/app/queue/member-shuffle') {
                    preferences = { mine: body.shuffle };
                    broadcast();
                }
                if (headers.destination === '/app/queue/reorder') {
                    if (reject) sendEvent('排序失败，请重试');
                    else {
                        const own = queue.filter(
                            (item) =>
                                item.enqueuedBy.token === 'mine' &&
                                item.priority === 'REGULAR' &&
                                item.music.platform !== 'netease-fm'
                        );
                        const moving = own.splice(
                            own.findIndex((item) => item.queueId === body.queueId),
                            1
                        )[0];
                        own.splice(
                            body.beforeQueueId === null
                                ? own.length
                                : own.findIndex((item) => item.queueId === body.beforeQueueId),
                            0,
                            moving
                        );
                        queue = queue.map((item) =>
                            item.enqueuedBy.token === 'mine' &&
                            item.priority === 'REGULAR' &&
                            item.music.platform !== 'netease-fm'
                                ? own.shift()
                                : item
                        );
                        broadcast();
                    }
                }
            }
        });
    });
    await page.goto('/', { waitUntil: 'commit' });
    await page.getByRole('button', { name: 'CONNECT', exact: true }).click();
    await expect(page.locator('#tutorial-random:visible, #tutorial-random-mobile:visible')).toBeVisible();
    return { sent, adminActions, errors, push: () => broadcast(), disconnect: () => disconnect(), queue: () => queue };
}

for (const mobile of [false, true]) {
    test(`${mobile ? 'mobile' : 'desktop'} chooses all four modes and shows only round-robin member controls`, async ({
        page,
    }) => {
        if (mobile) await page.setViewportSize({ width: 320, height: 800 });
        const fixture = await room(page);
        const trigger = page.locator(mobile ? '#tutorial-random-mobile' : '#tutorial-random');
        for (const [value, label] of [
            ['SHUFFLE', '完全随机'],
            ['ROUND_ROBIN', '轮询播放'],
            ['REPEAT_ONE', '单曲循环'],
            ['SEQUENTIAL', '顺序播放'],
        ]) {
            await trigger.click();
            const choice = page.getByRole('menuitemradio', { name: label, exact: true });
            await expect(choice).toBeVisible();
            const box = await page.getByRole('menu').boundingBox();
            expect(box.x).toBeGreaterThanOrEqual(0);
            expect(box.x + box.width).toBeLessThanOrEqual(page.viewportSize().width);
            const triggerBox = await trigger.boundingBox();
            expect(box.y + box.height).toBeLessThanOrEqual(triggerBox.y);
            await choice.click();
            await expect(trigger).toHaveAttribute('aria-label', `播放模式：${label}`);
            expect(fixture.sent.at(-1)).toEqual({ destination: '/app/control/play-mode', body: { playMode: value } });
        }
        expect(fixture.errors).toEqual([]);
    });
}

test('picker supports Escape, outside click and keyboard navigation', async ({ page }) => {
    await room(page);
    const trigger = page.locator('#tutorial-random');
    await trigger.click();
    await page.keyboard.press('Escape');
    await expect(trigger).toBeFocused();
    await expect(trigger).toHaveAttribute('aria-expanded', 'false');
    await trigger.press('ArrowUp');
    await page.keyboard.press('End');
    await page.keyboard.press('Enter');
    await expect(trigger).toHaveAttribute('aria-label', '播放模式：单曲循环');
    await trigger.click();
    await page.locator('h2').first().click();
    await expect(trigger).toHaveAttribute('aria-expanded', 'false');
});

test('round-robin personal preference defaults random and sends specified boolean', async ({ page }) => {
    const fixture = await room(page, { mode: 'ROUND_ROBIN' });
    await expect(page.getByText('轮询播放中', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '我的选曲：随机', exact: true }).click();
    await expect(page.getByRole('button', { name: '我的选曲：顺序', exact: true })).toBeVisible();
    await page.getByRole('button', { name: '我的选曲：顺序', exact: true }).click();
    expect(
        fixture.sent.filter(({ destination }) => destination === '/app/queue/member-shuffle').map(({ body }) => body)
    ).toEqual([{ shuffle: false }, { shuffle: true }]);
});

for (const mode of ['SEQUENTIAL', 'ROUND_ROBIN'])
    test(`${mode} keyboard and touch drag reorder only own regular songs`, async ({ page }) => {
        const fixture = await room(page, { mode });
        if (mode === 'ROUND_ROBIN') await page.getByRole('button', { name: 'Alice 5 SONGS', exact: true }).click();
        const queue = page.locator('#tutorial-queue:visible');
        await expect(queue.getByRole('button', { name: /^调整 / })).toHaveCount(3);
        const handle = queue.getByRole('button', { name: '调整 Song 3 的顺序', exact: true });
        await handle.press('ArrowUp');
        await expect
            .poll(() => fixture.queue().map(({ queueId }) => queueId))
            .toEqual(['q0', 'q1', 'q3', 'q2', 'q4', 'q5']);
        const source = queue.getByRole('button', { name: '调整 Song 2 的顺序', exact: true });
        const target = queue.locator('[data-queue-id="q0"]');
        const box = await target.boundingBox();
        await source.dispatchEvent('pointerdown', {
            pointerId: 9,
            pointerType: 'touch',
            button: 0,
            clientX: box.x + 20,
            clientY: box.y + 100,
        });
        await source.dispatchEvent('pointermove', {
            pointerId: 9,
            pointerType: 'touch',
            clientX: box.x + 30,
            clientY: box.y + 10,
        });
        await source.dispatchEvent('pointerup', {
            pointerId: 9,
            pointerType: 'touch',
            clientX: box.x + 30,
            clientY: box.y + 10,
        });
        await expect
            .poll(() => fixture.queue().map(({ queueId }) => queueId))
            .toEqual(['q2', 'q1', 'q0', 'q3', 'q4', 'q5']);
        expect(fixture.errors).toEqual([]);
    });

for (const input of ['mouse', 'touch']) {
    test(`real ${input} gesture reorders own entries without shifting another member`, async ({ page }) => {
        const fixture = await room(page);
        const handle = page.getByRole('button', { name: '调整 Song 3 的顺序', exact: true });
        const source = await handle.boundingBox();
        const target = await page.locator('[data-queue-id="q0"]').boundingBox();
        const start = { x: source.x + source.width / 2, y: source.y + source.height / 2 };
        const end = { x: target.x + 20, y: target.y + 8 };
        if (input === 'mouse') {
            await page.mouse.move(start.x, start.y);
            await page.mouse.down();
            await page.mouse.move(end.x, end.y, { steps: 12 });
            await page.mouse.up();
        } else {
            const session = await page.context().newCDPSession(page);
            await session.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [start] });
            await session.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [end] });
            await session.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
            await session.detach();
        }
        await expect
            .poll(() => fixture.queue().map((item) => item.queueId))
            .toEqual(['q3', 'q1', 'q0', 'q2', 'q4', 'q5']);
        expect(fixture.sent.filter((item) => item.destination === '/app/queue/reorder')).toHaveLength(1);
        expect(fixture.errors).toEqual([]);
    });
}

test('long virtual queue keeps scrolling past foreign rows after the source unmounts', async ({ page }) => {
    await room(page, { long: true });
    const handle = page.getByRole('button', { name: '调整 Long song 0 的顺序', exact: true });
    const source = await handle.boundingBox();
    const scroll = page.locator('#tutorial-queue [data-queue-scroll]');
    const box = await scroll.boundingBox();
    await page.mouse.move(source.x + 8, source.y + 8);
    await page.mouse.down();
    await page.mouse.move(box.x + 20, box.y + box.height - 15, { steps: 8 });
    await expect.poll(() => scroll.evaluate((el) => el.scrollTop)).toBeGreaterThan(1000);
    await expect(handle).toHaveCount(0);
    const before = await scroll.evaluate((el) => el.scrollTop);
    await expect.poll(() => scroll.evaluate((el) => el.scrollTop)).toBeGreaterThan(before + 200);
    await page.keyboard.press('Escape');
    const stopped = await scroll.evaluate((el) => el.scrollTop);
    await expect.poll(() => scroll.evaluate((el) => el.scrollTop)).toBe(stopped);
    await page.mouse.up();
});

test('server rejection leaves order intact and displays ERROR', async ({ page }) => {
    const fixture = await room(page, { reject: true });
    await page.getByRole('button', { name: '调整 Song 3 的顺序', exact: true }).press('ArrowUp');
    await expect(page.getByText('排序失败，请重试', { exact: true })).toBeVisible();
    expect(fixture.queue().map(({ queueId }) => queueId)).toEqual(['q0', 'q1', 'q2', 'q3', 'q4', 'q5']);
});

for (const guard of ['guest', 'locked'])
    test(`${guard} cannot change shared play mode`, async ({ page }) => {
        await room(page, { guest: guard === 'guest', locked: guard === 'locked', mode: 'ROUND_ROBIN' });
        await expect(page.locator('#tutorial-random')).toBeDisabled();
        if (guard === 'guest') {
            await expect(page.getByRole('button', { name: '我的选曲：随机', exact: true })).toBeDisabled();
            await page.getByRole('button', { name: 'Alice 5 SONGS', exact: true }).click();
            await expect(page.getByRole('button', { name: '调整 Song 0 的顺序', exact: true })).toBeDisabled();
        }
    });

test('admin selects explicit modes and has no legacy fair switch', async ({ page }) => {
    const fixture = await room(page, { admin: true, locked: true });
    await page.getByPlaceholder('ENTER ADMIN PASSWORD').fill('test-admin');
    await page.getByRole('button', { name: 'ACCESS SYSTEM' }).click();
    const select = page.getByRole('combobox', { name: '管理员播放模式' });
    await select.selectOption('ROUND_ROBIN');
    await expect(select).toHaveValue('ROUND_ROBIN');
    expect(fixture.adminActions).toEqual([{ action: 'SET_PLAY_MODE', playMode: 'ROUND_ROBIN' }]);
    await expect(page.getByText('公平模式', { exact: true })).toHaveCount(0);
});
