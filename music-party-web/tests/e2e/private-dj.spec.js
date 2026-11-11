import { test, expect } from '@playwright/test';

async function room(page, settings, reject = false) {
    const config = { privateDj: { source: 'selected-provider', mode: 'OFF', ...settings } };
    const updates = [];
    let broadcast;
    await page.addInitScript(() => {
        localStorage.setItem('mp_username', 'Admin');
        localStorage.setItem('mp_user_token', 'admin-user');
        localStorage.setItem('mp_tutorial_done_v1', 'true');
        localStorage.setItem('mp_pwa_hint_shown', 'true');
    });
    await page.route('**/api/**', (route) => {
        const path = new URL(route.request().url()).pathname;
        let body = {};
        if (path === '/api/auth/status') body = { isSetup: true, hasProtection: false };
        if (path === '/api/config') body = { authorName: 'Fixture' };
        if (path === '/api/v2/sources')
            body = {
                schemaVersion: 2,
                sources: [
                    {
                        id: 'other-dj-provider',
                        label: 'Other DJ provider',
                        capabilities: { recommendations: true },
                        recommendationModes: ['FM', 'DJ'],
                        collectionCategories: [],
                        delivery: { mode: 'DIRECT_URL', supportedModes: ['DIRECT_URL'] },
                    },
                ],
            };
        if (path === '/api/admin/verify') body = { message: 'VERIFIED' };
        if (path === '/api/admin/private-dj') {
            const update = route.request().postDataJSON();
            updates.push(update);
            if (reject) return route.fulfill({ status: 400, json: { message: 'Selected provider disabled' } });
            Object.assign(config.privateDj, update);
            broadcast();
            body = { message: 'Saved' };
        }
        return route.fulfill({ json: body });
    });
    await page.routeWebSocket('**/ws', (socket) => {
        const subscriptions = new Map();
        let sequence = 0;
        const send = (destination, body) => {
            for (const [id, topic] of subscriptions)
                if (topic === destination)
                    socket.send(
                        `MESSAGE\nsubscription:${id}\nmessage-id:${++sequence}\ndestination:${destination}\n\n${JSON.stringify(body)}\0`
                    );
        };
        broadcast = () =>
            send('/topic/player/state', {
                nowPlaying: null,
                queue: [],
                onlineUsers: [],
                playMode: 'SEQUENTIAL',
                config,
            });
        socket.onMessage((raw) => {
            for (const frame of String(raw).split('\0')) {
                const [head] = frame.split('\n\n'),
                    lines = head.trim().split('\n'),
                    command = lines.shift();
                const headers = Object.fromEntries(
                    lines.map((line) => {
                        const at = line.indexOf(':');
                        return [line.slice(0, at), line.slice(at + 1)];
                    })
                );
                if (command === 'CONNECT')
                    socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\nsession:admin-user\n\n\0');
                if (command === 'SUBSCRIBE') {
                    subscriptions.set(headers.id, headers.destination);
                    if (headers.destination === '/app/user/me')
                        send(headers.destination, { name: 'Admin', sessionId: 'admin-user', isGuest: false });
                }
                if (command === 'SEND' && headers.destination === '/app/player/resync') {
                    broadcast();
                    send('/topic/player/events', { action: 'ADMIN_TRIGGER', userId: 'admin-user', level: 'INFO' });
                }
            }
        });
    });
    await page.goto('/');
    await page.getByRole('button', { name: 'CONNECT', exact: true }).click();
    await page.getByPlaceholder('ENTER ADMIN PASSWORD').fill('admin-secret');
    await page.getByRole('button', { name: 'ACCESS SYSTEM' }).click();
    return {
        updates,
        change: (settings) => {
            config.privateDj = { ...config.privateDj, ...settings };
            broadcast();
        },
    };
}

test('FM-only deployment hides DJ despite another enabled DJ-capable catalog source', async ({ page }) => {
    const server = await room(page, { fmSupported: true, djSupported: false });
    const fm = page.getByRole('button', { name: '私人FM', exact: true });
    await expect(fm).toBeVisible();
    await expect(page.getByRole('button', { name: '私人DJ', exact: true })).toHaveCount(0);
    await expect(page.getByText(/私人DJ模式=先播语音再播歌/)).toHaveCount(0);
    await expect(page.getByText(/播FM\/DJ/)).toHaveCount(0);
    await fm.click();
    await expect.poll(() => server.updates).toEqual([{ mode: 'FM' }]);
    await fm.click();
    await expect.poll(() => server.updates).toEqual([{ mode: 'FM' }, { mode: 'OFF' }]);
});

test('both supported modes render and updated backend capabilities hide DJ immediately', async ({ page }) => {
    const server = await room(page, { fmSupported: true, djSupported: true });
    const dj = page.getByRole('button', { name: '私人DJ', exact: true });
    await expect(dj).toBeVisible();
    await expect(page.getByRole('button', { name: '私人FM', exact: true })).toBeVisible();
    await expect(page.getByText(/私人DJ模式=先播语音再播歌/)).toBeVisible();
    await dj.click();
    await expect.poll(() => server.updates).toEqual([{ mode: 'DJ' }]);
    server.change({ source: 'fm-only', djSupported: false });
    await expect(dj).toHaveCount(0);
    await expect(page.getByText(/私人DJ模式=先播语音再播歌/)).toHaveCount(0);
    await page.getByRole('button', { name: '关闭私人电台', exact: true }).click();
    await expect.poll(() => server.updates).toEqual([{ mode: 'DJ' }, { mode: 'OFF' }]);
});

for (const settings of [{ fmSupported: false, djSupported: false }, {}]) {
    test(`unsupported or legacy deployment fails closed ${JSON.stringify(settings)}`, async ({ page }) => {
        const server = await room(page, settings);
        await expect(page.getByRole('button', { name: '私人FM', exact: true })).toHaveCount(0);
        await expect(page.getByRole('button', { name: '私人DJ', exact: true })).toHaveCount(0);
        await expect(page.getByText('部署指定的推荐音源未启用或不支持私人电台')).toBeVisible();
        expect(server.updates).toEqual([]);
    });
}

test('authoritative backend rejection is displayed without optimistic mode changes', async ({ page }) => {
    const server = await room(page, { fmSupported: true, djSupported: true }, true);
    await page.getByRole('button', { name: '私人DJ', exact: true }).click();
    await expect(page.getByText('Selected provider disabled', { exact: true })).toBeVisible();
    expect(server.updates).toEqual([{ mode: 'DJ' }]);
    await expect(page.getByRole('button', { name: '关闭私人电台', exact: true })).toHaveCount(0);
});
