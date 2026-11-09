import { test, expect } from '@playwright/test';

async function room(page, { admin = false, guest = false, rejectClear = false } = {}) {
  const owner = { token: 'member-A', sessionId: 'session-A', name: guest ? '游客' : 'Alice', isGuest: guest };
  const other = { token: 'member-B', sessionId: 'session-B', name: 'Bob', isGuest: false };
  let queue = [owner, owner, other].map((user, i) => ({ queueId: `q${i}`, enqueuedBy: user,
    music: { id: String(i), name: `Song ${i}`, artists: ['Test'], duration: 180000, platform: 'netease', coverUrl: '' }, status: 'READY', priority: 'REGULAR' }));
  const sent = [];
  const updates = [];
  const config = { retainPlaylist: false, neteaseEnabled: true, bilibiliEnabled: true, maxQueueSize: 1000,
    maxUserSongs: 100, privateDj: { mode: 'OFF' } };
  let broadcast = () => {};
  await page.addInitScript(({ guest }) => {
    localStorage.setItem('mp_username', guest ? '游客' : 'Alice');
    localStorage.setItem('mp_user_token', 'member-A');
    localStorage.setItem('mp_tutorial_done_v1', 'true');
    localStorage.setItem('mp_pwa_hint_shown', 'true');
    // Exercise HTTP/LAN request ID fallback.
    Object.defineProperty(crypto, 'randomUUID', { value: undefined });
  }, { guest });
  await page.route('https://**', route => route.abort());
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname;
    let body = {};
    if (path === '/api/auth/status') body = { isSetup: true, hasProtection: false };
    if (path === '/api/config') body = { authorName: 'Test' };
    if (path === '/api/admin/verify') body = { message: 'VERIFIED' };
    if (path === '/api/admin/config/update') {
      const update = route.request().postDataJSON(); updates.push(update);
      Object.assign(config, update); broadcast(); body = { message: '系统配置已刷新' };
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  });
  await page.routeWebSocket('**/ws', socket => {
    const subscriptions = new Map(); let sequence = 0;
    const send = (destination, body) => {
      for (const [id, topic] of subscriptions) if (topic === destination)
        socket.send(`MESSAGE\nsubscription:${id}\nmessage-id:${++sequence}\ndestination:${destination}\n\n${JSON.stringify(body)}\0`);
    };
    broadcast = () => {
      send('/topic/player/queue', queue);
      send('/topic/player/state', { nowPlaying: null, queue, playMode: 'SHUFFLE', isFairShuffle: true,
        isPaused: false, onlineUsers: [owner, other], config });
    };
    socket.onMessage(raw => {
      for (const frame of String(raw).split('\0')) {
        const [head, json] = frame.split('\n\n');
        const lines = head.trim().split('\n'); const command = lines.shift();
        const headers = Object.fromEntries(lines.map(line => { const at = line.indexOf(':'); return [line.slice(0, at), line.slice(at + 1)]; }));
        if (command === 'CONNECT') socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\nsession:session-A\n\n\0');
        if (command === 'SUBSCRIBE') {
          subscriptions.set(headers.id, headers.destination);
          if (headers.destination === '/app/user/me') send(headers.destination, owner);
          if (headers.destination === '/app/chat/history') send(headers.destination, []);
        }
        if (command !== 'SEND') continue;
        const body = JSON.parse(json || '{}'); sent.push({ destination: headers.destination, body });
        if (headers.destination === '/app/player/resync') {
          broadcast();
          if (admin) send('/topic/player/events', { action: 'ADMIN_TRIGGER', userId: 'member-A', level: 'INFO' });
        }
        if (headers.destination === '/app/queue/clear-mine') {
          const removed = queue.filter(item => item.enqueuedBy.token === owner.token).length;
          if (!rejectClear) queue = queue.filter(item => item.enqueuedBy.token !== owner.token);
          broadcast();
          send('/user/queue/playlist/clear', { requestId: body.requestId, removed: rejectClear ? 0 : removed,
            error: rejectClear, message: rejectClear ? '请先设置昵称并加入房间' : `已清空我的歌单，移除 ${removed} 首` });
        }
      }
    });
  });
  await page.goto('/', { waitUntil: 'commit' });
  await page.getByRole('button', { name: 'CONNECT', exact: true }).click();
  return { sent, updates };
}

test('admin retention switch sends partial updates and follows shared state after reload', async ({ page }) => {
  const fixture = await room(page, { admin: true });
  await page.getByPlaceholder('ENTER ADMIN PASSWORD').fill('test-admin');
  await page.getByRole('button', { name: 'ACCESS SYSTEM' }).click();
  const control = page.getByRole('switch', { name: '播完保留歌单' });
  await expect(control).toHaveAttribute('aria-checked', 'false');
  await control.click();
  await expect(control).toHaveAttribute('aria-checked', 'true');
  expect(fixture.updates).toEqual([{ retainPlaylist: true }]);
  await page.reload({ waitUntil: 'commit' });
  await page.getByRole('button', { name: 'CONNECT', exact: true }).click();
  await expect(control).toHaveAttribute('aria-checked', 'true');
  await control.click();
  await expect(control).toHaveAttribute('aria-checked', 'false');
  expect(fixture.updates).toEqual([{ retainPlaylist: true }, { retainPlaylist: false }]);
});

for (const mobile of [false, true]) test(`${mobile ? '320px mobile' : 'desktop'} clears own songs and leaves other member`, async ({ page }) => {
  if (mobile) await page.setViewportSize({ width: 320, height: 800 });
  const fixture = await room(page);
  if (mobile) await page.locator('#tutorial-queue-mobile').click();
  await page.getByRole('button', { name: '清空我的歌单（2）' }).click();
  await expect(page.getByText('已清空我的歌单，移除 2 首', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '清空我的歌单（0）' })).toBeDisabled();
  const queue = page.locator('#tutorial-queue:visible');
  await expect(queue.getByRole('button', { name: 'Bob 1 SONGS', exact: true })).toBeVisible();
  await expect(queue.getByText('Alice', { exact: true })).toHaveCount(0);
  const clear = fixture.sent.filter(item => item.destination === '/app/queue/clear-mine');
  expect(clear).toHaveLength(1);
  expect(Object.keys(clear[0].body)).toEqual(['requestId']);
  expect(clear[0].body.requestId).toBeTruthy();
});

test('server rejection keeps playlist and enables retry', async ({ page }) => {
  const fixture = await room(page, { rejectClear: true });
  const clear = page.getByRole('button', { name: '清空我的歌单（2）' });
  await clear.click();
  await expect(page.getByText('请先设置昵称并加入房间', { exact: true })).toBeVisible();
  await expect(clear).toBeEnabled();
  expect(fixture.sent.filter(item => item.destination === '/app/queue/clear-mine')).toHaveLength(1);
});

test('guest cannot clear playlist', async ({ page }) => {
  const fixture = await room(page, { guest: true });
  await expect(page.getByRole('button', { name: '清空我的歌单（2）' })).toBeDisabled();
  expect(fixture.sent.filter(item => item.destination === '/app/queue/clear-mine')).toHaveLength(0);
});
