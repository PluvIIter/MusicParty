import { compactFrames } from './compact-fixture';
import { test, expect } from '@playwright/test';

const descriptor = (id, album = false) => ({
    id,
    label: id,
    capabilities: { search: true, userSearch: true, personalCollections: true, album },
    delivery: { mode: 'DIRECT_URL', supportedModes: ['DIRECT_URL'] },
    collectionCategories: [
        { id: 'public-lists', label: 'Public lists', entryKind: 'CONTAINER', collectionType: 'mix' },
        { id: 'public-tracks', label: 'Public tracks', entryKind: 'TRACK', collectionType: 'track' },
    ],
});
const track = (name, platform = 'future-source') => ({
    id: 'same',
    name,
    platform,
    artists: ['Snapshot artist'],
    duration: 1000,
    externalUrl: 'javascript:alert(1)',
});
async function fixture(page, { emptyFirst = false, deferCollections = false, deferTracks = false } = {}) {
    const reads = [],
        sent = [],
        pending = [];
    let push;
    await page.addInitScript(() => {
        localStorage.setItem('mp_username', 'PublicTester');
        localStorage.setItem('mp_user_token', 'tester');
        localStorage.setItem('mp_tutorial_done_v1', 'true');
        localStorage.setItem('mp_pwa_hint_shown', 'true');
        localStorage.setItem(
            'mp_liked_songs',
            JSON.stringify([
                {
                    id: 'same',
                    name: 'Disabled snapshot',
                    platform: 'disabled-source',
                    artists: ['Saved artist'],
                    externalUrl: 'javascript:alert(1)',
                },
            ])
        );
    }, {});
    await page.route('**/api/**', async (route) => {
        const url = new URL(route.request().url()),
            path = url.pathname;
        reads.push(url);
        let body = {};
        if (path === '/api/auth/status') body = { isSetup: true, hasProtection: false };
        else if (path === '/api/config') body = { authorName: 'Test' };
        else if (path === '/api/v2/sources')
            body = {
                schemaVersion: 2,
                sources: [descriptor('future-source', true), descriptor('other-source'), descriptor('disabled-source')],
            };
        else if (path.endsWith('/users/search'))
            body = { items: [{ id: 'public-user', name: 'Public user' }], nextCursor: null };
        else if (path.endsWith('/tracks/search')) {
            if (url.searchParams.get('q') === 'deferred')
                return new Promise((resolve) =>
                    pending.push(async () => {
                        await route
                            .fulfill({ json: { items: [track('Stale search')], nextCursor: null } })
                            .catch(() => {});
                        resolve();
                    })
                );
            body = {
                items: [track('Search song', path.includes('/other-source/') ? 'other-source' : 'future-source')],
                nextCursor: null,
            };
        } else if (path.includes('/collections/')) {
            if (!url.searchParams.get('userId'))
                return route.fulfill({ status: 400, json: { message: 'Explicit user required' } });
            body = path.endsWith('/public-tracks')
                ? { items: [track('Public track')], nextCursor: null }
                : {
                      items: [
                          {
                              id: 'mix/1',
                              name: 'Public mix',
                              platform: 'future-source',
                              collectionType: 'mix',
                              trackCount: 2,
                          },
                      ],
                      nextCursor: null,
                  };
        } else if (path.includes('/containers/') && path.endsWith('/tracks'))
            body =
                emptyFirst && !url.searchParams.get('cursor')
                    ? { items: [], nextCursor: 'second-page' }
                    : { items: [track('Container song')], nextCursor: null };
        else if (path.endsWith('/album'))
            body = {
                id: 'album',
                name: 'Future album',
                platform: 'future-source',
                collectionType: 'album',
                trackCount: 1,
            };
        if (
            (deferCollections && path.includes('/collections/')) ||
            (deferTracks && path.includes('/containers/') && path.endsWith('/tracks'))
        ) {
            return new Promise((resolve) =>
                pending.push(async () => {
                    await route.fulfill({ json: body }).catch(() => {});
                    resolve();
                })
            );
        }
        return route.fulfill({ json: body });
    });
    await page.routeWebSocket('**/ws', (socket) => {
        const subscriptions = new Map();
        let sequence = 0;
        const snapshot = compactFrames();
        const sendState = (state) => send('/topic/player/compact', snapshot(state));
        const send = (destination, body) => {
            for (const [id, topic] of subscriptions)
                if (topic === destination)
                    socket.send(
                        `MESSAGE\nsubscription:${id}\nmessage-id:${++sequence}\ndestination:${destination}\n\n${JSON.stringify(body)}\0`
                    );
        };
        push = () =>
            sendState({
                nowPlaying: null,
                queue: [
                    {
                        queueId: 'other',
                        music: track('Other source queued', 'other-source'),
                        enqueuedBy: { token: 'tester', name: 'PublicTester' },
                        priority: 'REGULAR',
                        status: 'READY',
                    },
                ],
                onlineUsers: [],
                playMode: 'SEQUENTIAL',
                config: {
                    sources: {
                        'future-source': { enabled: true },
                        'other-source': { enabled: true },
                        'disabled-source': { enabled: false },
                    },
                    privateDj: { mode: 'OFF' },
                },
            });
        socket.onMessage((raw) => {
            for (const frame of String(raw).split('\0')) {
                const [head, json] = frame.split('\n\n'),
                    lines = head.trim().split('\n'),
                    command = lines.shift();
                const headers = Object.fromEntries(
                    lines.map((line) => {
                        const at = line.indexOf(':');
                        return [line.slice(0, at), line.slice(at + 1)];
                    })
                );
                if (command === 'CONNECT') socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\nsession:tester\n\n\0');
                if (command === 'SUBSCRIBE') {
                    subscriptions.set(headers.id, headers.destination);
                    if (headers.destination === '/app/user/me')
                        send(headers.destination, { sessionId: 'tester', name: 'PublicTester', isGuest: false });
                }
                if (command === 'SEND') {
                    sent.push({ destination: headers.destination, body: JSON.parse(json || '{}') });
                    if (headers.destination === '/app/player/compact-resync') push();
                }
            }
        });
    });
    await page.goto('/');
    await page.getByRole('button', { name: 'CONNECT', exact: true }).click();
    await page.locator('#tutorial-search').click();
    await expect(page.getByRole('button', { name: 'future-source', exact: true })).toBeVisible();
    return { reads, sent, release: () => Promise.all(pending.splice(0).map((fn) => fn())) };
}

test('empty first container page has an explicit reachable next-page action', async ({ page }) => {
    const server = await fixture(page, { emptyFirst: true });
    await page.getByRole('textbox', { name: '公开用户 ID', exact: true }).fill('uid');
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await page.getByText('Public mix', { exact: true }).click();
    const more = page.getByRole('button', { name: '加载更多曲目', exact: true });
    await expect(more).toBeVisible();
    await more.click();
    await expect(page.getByText('Container song', { exact: true })).toBeVisible();
    expect(server.reads.some((url) => url.searchParams.get('cursor') === 'second-page')).toBe(true);
});

for (const switchToLikes of [false, true])
    test(`canceled collections resume after ${switchToLikes ? 'LIKESONG' : 'close'} without changing the binding`, async ({
        page,
    }) => {
        const server = await fixture(page, { deferCollections: true });
        await page.getByRole('textbox', { name: '公开用户 ID', exact: true }).fill('uid');
        await page.getByRole('button', { name: '保存', exact: true }).click();
        await expect.poll(() => server.reads.filter((url) => url.pathname.includes('/collections/')).length).toBe(1);
        if (switchToLikes) await page.getByRole('button', { name: '点赞的音乐', exact: true }).click();
        else await page.getByRole('button', { name: '关闭搜索', exact: true }).click();
        await server.release();
        if (switchToLikes) await page.getByRole('button', { name: 'future-source', exact: true }).click();
        else await page.locator('#tutorial-search').click();
        await expect.poll(() => server.reads.filter((url) => url.pathname.includes('/collections/')).length).toBe(2);
        await server.release();
        await expect(page.getByText('Public mix', { exact: true })).toBeVisible();
    });

test('canceled container tracks resume on reopen without a new source selection', async ({ page }) => {
    const server = await fixture(page, { deferTracks: true });
    await page.getByRole('textbox', { name: '公开用户 ID', exact: true }).fill('uid');
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await page.getByText('Public mix', { exact: true }).click();
    await expect.poll(() => server.reads.filter((url) => url.pathname.includes('/containers/')).length).toBe(1);
    await page.getByRole('button', { name: '关闭搜索', exact: true }).click();
    await server.release();
    await page.locator('#tutorial-search').click();
    await expect.poll(() => server.reads.filter((url) => url.pathname.includes('/containers/')).length).toBe(2);
    await server.release();
    await expect(page.getByText('Container song', { exact: true })).toBeVisible();
});

test('unknown source discovers generic public categories with explicit browser-only targets', async ({ page }) => {
    const server = await fixture(page);
    await page.getByRole('textbox', { name: '公开用户 ID', exact: true }).fill('uid-A');
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await expect(page.getByText('Public mix', { exact: true })).toBeVisible();
    expect(
        server.reads
            .filter((url) => url.pathname.includes('/collections/'))
            .every((url) => url.searchParams.get('userId') === 'uid-A')
    ).toBe(true);
    await page.getByText('Public mix', { exact: true }).click();
    await expect(page.getByText('Container song', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '添加单曲：Container song', exact: true })).toBeEnabled();
    await page.getByRole('button', { name: '添加单曲：Container song', exact: true }).click();
    await expect
        .poll(() =>
            server.sent.some((item) => item.destination === '/app/enqueue' && item.body.platform === 'future-source')
        )
        .toBe(true);
    await page.getByRole('combobox', { name: '收藏分类' }).selectOption('public-tracks');
    await expect(page.getByText('Public track', { exact: true })).toBeVisible();
    expect(server.sent.some((item) => item.destination === '/app/user/bind')).toBe(false);
    expect(server.reads.some((url) => url.pathname === '/api/user/albums/netease')).toBe(false);
    await expect(page.getByText('收藏专辑', { exact: true })).toHaveCount(0);
});

test('deferred source response cannot overwrite current container results', async ({ page }) => {
    const server = await fixture(page);
    await page.getByPlaceholder('搜索音乐...').fill('deferred');
    await page.getByPlaceholder('搜索音乐...').press('Enter');
    await expect.poll(() => server.reads.some((url) => url.searchParams.get('q') === 'deferred')).toBe(true);
    await page.getByRole('textbox', { name: '公开用户 ID', exact: true }).fill('uid-B');
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await page.getByText('Public mix', { exact: true }).click();
    await expect(page.getByText('Container song', { exact: true })).toBeVisible();
    await server.release();
    await expect(page.getByText('Container song', { exact: true })).toBeVisible();
    await expect(page.getByText('Stale search', { exact: true })).toHaveCount(0);
});

test('disabled source LIKESONG metadata remains visible and local storage unchanged', async ({ page }) => {
    const server = await fixture(page);
    await page.getByRole('button', { name: '点赞的音乐', exact: true }).click();
    await expect(page.getByText('Disabled snapshot', { exact: true })).toBeVisible();
    await expect(page.getByText('Saved artist', { exact: true })).toBeVisible();
    const saved = await page.evaluate(() => localStorage.getItem('mp_liked_songs'));
    await page.getByTitle('打开源页面').click();
    expect(page.context().pages()).toHaveLength(1);
    expect(await page.evaluate(() => localStorage.getItem('mp_liked_songs'))).toBe(saved);
    expect(server.sent.some((item) => item.destination === '/app/enqueue')).toBe(false);
    expect(server.reads.some((url) => url.pathname.endsWith('/external-url'))).toBe(false);
});
