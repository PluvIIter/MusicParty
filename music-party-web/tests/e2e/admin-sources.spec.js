import { test, expect } from '@playwright/test';
import { compactFrames } from './compact-fixture';

async function room(page, { reject = false, initialConfigured = false } = {}) {
    let configured = initialConfigured,
        broadcast;
    const saves = [],
        reads = [],
        config = {
            sources: { qqmusic: { enabled: false }, 'unknown-source': { enabled: true } },
            privateDj: { mode: 'OFF', source: 'qqmusic', fmSupported: true, djSupported: false },
        };
    await page.addInitScript(() => {
        localStorage.setItem('mp_username', 'Admin');
        localStorage.setItem('mp_user_token', 'admin');
        localStorage.setItem('mp_tutorial_done_v1', 'true');
        localStorage.setItem('mp_pwa_hint_shown', 'true');
    });
    await page.route('**/api/**', (route) => {
        const path = new URL(route.request().url()).pathname;
        reads.push({ path, headers: route.request().headers() });
        let body = {};
        if (path === '/api/auth/status') body = { isSetup: true, hasProtection: false };
        if (path === '/api/config') body = { authorName: 'Fixture' };
        if (path === '/api/v2/sources')
            body = {
                sources: [
                    {
                        id: 'unknown-source',
                        label: '未知源',
                        capabilities: {},
                        collectionCategories: [],
                        delivery: { mode: 'DIRECT_URL' },
                    },
                ],
            };
        if (path === '/api/admin/verify') body = { message: 'VERIFIED' };
        if (path === '/api/admin/sources') {
            expect(route.request().headers()['x-admin-password']).toBe('admin-password');
            body = {
                sources: [
                    {
                        id: 'qqmusic',
                        label: 'QQ音乐',
                        enabled: config.sources.qqmusic.enabled,
                        credential: { type: 'qqmusic-credential', label: 'QQ音乐登录凭据（JSON）', configured },
                    },
                    { id: 'unknown-source', label: '未知源', enabled: true, credential: null },
                ],
            };
        }
        if (path === '/api/admin/config/cookie') {
            saves.push(route.request().postDataJSON());
            if (reject) return route.fulfill({ status: 400, json: { message: 'reflected-secret-value' } });
            configured = route.request().postDataJSON().value !== '';
            body = { message: 'reflected-secret-value' };
        }
        if (path === '/api/admin/config/update') {
            const update = route.request().postDataJSON();
            Object.assign(config.sources.qqmusic, update.sources.qqmusic);
            broadcast();
            body = { message: 'Saved' };
        }
        return route.fulfill({ json: body });
    });
    await page.routeWebSocket('**/ws', (socket) => {
        const subscriptions = new Map(),
            snapshot = compactFrames();
        let sequence = 0;
        const send = (destination, body) => {
            for (const [id, topic] of subscriptions)
                if (topic === destination)
                    socket.send(
                        `MESSAGE\nsubscription:${id}\nmessage-id:${++sequence}\ndestination:${destination}\n\n${JSON.stringify(body)}\0`
                    );
        };
        broadcast = () =>
            send(
                '/topic/player/compact',
                snapshot({
                    nowPlaying: null,
                    queue: [],
                    onlineUsers: [],
                    playMode: 'SEQUENTIAL',
                    isPaused: false,
                    config,
                })
            );
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
                if (command === 'CONNECT') socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\nsession:admin\n\n\0');
                if (command === 'SUBSCRIBE') {
                    subscriptions.set(headers.id, headers.destination);
                    if (headers.destination === '/app/user/me')
                        send(headers.destination, { name: 'Admin', sessionId: 'admin', isGuest: false });
                }
                if (command === 'SEND' && headers.destination === '/app/player/compact-resync') {
                    broadcast();
                    send('/topic/player/events', { action: 'ADMIN_TRIGGER', userId: 'admin', message: 'Admin' });
                }
            }
        });
    });
    await page.goto('/');
    await page.getByRole('button', { name: 'CONNECT', exact: true }).click();
    expect(reads.some((item) => item.path === '/api/admin/sources')).toBe(false);
    await page.getByPlaceholder('ENTER ADMIN PASSWORD').fill('admin-password');
    await page.getByRole('button', { name: 'ACCESS SYSTEM' }).click();
    return { saves, reads };
}

test('disabled QQ credentials are admin-managed without exposing stored values and reload state is metadata only', async ({
    page,
}) => {
    const server = await room(page);
    const input = page.getByLabel('QQ音乐登录凭据（JSON）', { exact: true });
    await expect(input).toBeVisible();
    await expect(input).toHaveAttribute('type', 'password');
    await expect(input).toHaveValue('');
    await expect(page.getByRole('switch', { name: '启用音乐源：QQ音乐' })).toHaveAttribute('aria-checked', 'false');
    await expect(page.getByRole('button', { name: '私人FM', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '私人DJ', exact: true })).toHaveCount(0);
    const credential = '{"musicid":"123","musickey":"reflected-secret-value"}';
    await input.fill(credential);
    await page.getByRole('button', { name: '更新QQ音乐登录凭据（JSON）', exact: true }).click();
    await expect(input).toHaveValue('');
    await expect(page.getByText('凭据已更新', { exact: true })).toBeVisible();
    expect(server.saves).toEqual([{ platform: 'qqmusic', value: credential }]);
    await expect(page.getByText('reflected-secret-value', { exact: true })).toHaveCount(0);
    expect(await page.evaluate(() => Object.values(localStorage).join(' '))).not.toContain('reflected-secret-value');
    await page.reload();
    await page.getByRole('button', { name: 'CONNECT', exact: true }).click();
    await expect(input).toHaveValue('');
    await expect(page.getByText('已配置', { exact: true })).toBeVisible();
});

test('clearing saved credentials requires confirmation and sends an explicit empty value', async ({ page }) => {
    const server = await room(page, { initialConfigured: true });
    const clear = page.getByRole('button', { name: '清除QQ音乐登录凭据（JSON）', exact: true });
    const input = page.getByLabel('QQ音乐登录凭据（JSON）', { exact: true });
    await input.fill('draft-secret');
    page.once('dialog', (dialog) => dialog.dismiss());
    await clear.click();
    expect(server.saves).toEqual([]);
    await expect(page.getByText('已配置', { exact: true })).toBeVisible();
    page.once('dialog', (dialog) => dialog.accept());
    await clear.click();
    await expect(page.getByText('凭据已清除', { exact: true })).toBeVisible();
    expect(server.saves).toEqual([{ platform: 'qqmusic', value: '' }]);
    await expect(input).toHaveValue('');
    await expect(page.getByText('未配置', { exact: true })).toBeVisible();
    await expect(clear).toHaveCount(0);
});

test('a rejected credential clear preserves configured status and displays only a safe error', async ({ page }) => {
    await room(page, { initialConfigured: true, reject: true });
    const clear = page.getByRole('button', { name: '清除QQ音乐登录凭据（JSON）', exact: true });
    page.once('dialog', (dialog) => dialog.accept());
    await clear.click();
    await expect(page.getByText('凭据更新失败，请检查格式、权限或服务状态后重试', { exact: true })).toBeVisible();
    await expect(page.getByText('已配置', { exact: true })).toBeVisible();
    await expect(clear).toBeEnabled();
    await expect(page.getByText('reflected-secret-value', { exact: true })).toHaveCount(0);
});

test('credential errors never reflect secrets and unsupported sources have no credential editor', async ({ page }) => {
    await room(page, { reject: true });
    const input = page.getByLabel('QQ音乐登录凭据（JSON）', { exact: true });
    await input.fill('reflected-secret-value');
    await page.getByRole('button', { name: '更新QQ音乐登录凭据（JSON）', exact: true }).click();
    await expect(page.getByText('凭据更新失败，请检查格式、权限或服务状态后重试', { exact: true })).toBeVisible();
    await expect(page.getByText('reflected-secret-value', { exact: true })).toHaveCount(0);
    await expect(page.getByLabel(/未知源.*凭据/)).toHaveCount(0);
});
