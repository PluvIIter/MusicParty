import { test, expect } from '@playwright/test';

async function room(page, { admin = false, guest = true, enabled = true, rejectClaim = false, sourceOnline = false, mode = 'SHUFFLE', failSetting = false } = {}) {
  const owner = { token: 'current', sessionId: 'session-current', name: guest ? '游客' : 'Current', isGuest: guest };
  const original = { token: 'lost', sessionId: 'old', name: 'Original', isGuest: false };
  const other = { token: 'other', sessionId: 'session-other', name: 'Other', isGuest: false };
  const pinned = { token: 'pinned', sessionId: 'old-pin', name: 'Pinned', isGuest: false };
  let queue = [owner, original, original, original, other, pinned].map((member, i) => ({ queueId: `q${i}`, enqueuedBy: { ...member },
    music: { id: String(i), name: `Song ${i}`, artists: ['Fixture'], duration: 180000, platform: 'netease', coverUrl: '' }, status: 'READY', priority: [1, 5].includes(i) ? 'GLOBAL_TOP' : i === 3 ? 'USER_TOP' : 'REGULAR' }));
  const sent = [], updates = [];
  const config = { allowPlaylistClaim: enabled, neteaseEnabled: true, bilibiliEnabled: true, maxQueueSize: 1000, maxUserSongs: 100, privateDj: { mode: 'OFF' } };
  let broadcast = () => {};
  await page.addInitScript(() => {
    if (!localStorage.getItem('fixture-initialized')) {
      localStorage.setItem('mp_user_token', 'current');localStorage.setItem('fixture-initialized', 'true');
    }
    localStorage.setItem('mp_tutorial_done_v1', 'true');localStorage.setItem('mp_pwa_hint_shown', 'true');localStorage.setItem('mp_auto_lite_mode', 'false');
    Object.defineProperty(crypto, 'randomUUID', { value: undefined });
  });
  await page.route('https://**', route => route.abort());
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname;
    let body = {};
    if (path === '/api/auth/status') body = { isSetup: true, hasProtection: false };
    if (path === '/api/config') body = { authorName: 'Fixture' };
    if (path === '/api/admin/verify') body = { message: 'VERIFIED' };
    if (path === '/api/admin/playlist-claim') {
      expect(route.request().headers()['x-admin-password']).toBe('test-admin');
      const update = route.request().postDataJSON(); updates.push(update);
      if (failSetting) return route.fulfill({ status: 500, contentType: 'application/json', body: '{}' });
      config.allowPlaylistClaim = update.allowPlaylistClaim; broadcast();body = { allowPlaylistClaim: config.allowPlaylistClaim, message: '歌单认领设置已保存并生效' };
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
      send('/topic/player/state', { nowPlaying: null, queue, playMode: mode, isFairShuffle: true, isPaused: true,
        onlineUsers: [owner, other, ...(sourceOnline ? [original] : [])], config });
    };
    socket.onMessage(raw => {
      for (const frame of String(raw).split('\0')) {
        const [head, json] = frame.split('\n\n');
        const lines = head.trim().split('\n');const command = lines.shift();
        const headers = Object.fromEntries(lines.map(line => { const at = line.indexOf(':');return [line.slice(0, at), line.slice(at + 1)]; }));
        if (command === 'CONNECT') socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\nsession:session-current\n\n\0');
        if (command === 'SUBSCRIBE') {
          subscriptions.set(headers.id, headers.destination);
          if (headers.destination === '/app/user/me') send(headers.destination, owner);
          if (headers.destination === '/app/chat/history') send(headers.destination, []);
        }
        if (command !== 'SEND') continue;
        const body = JSON.parse(json || '{}');sent.push({ destination: headers.destination, body });
        if (headers.destination === '/app/player/resync') {
          broadcast();if (admin) send('/topic/player/events', { action: 'ADMIN_TRIGGER', userId: owner.token, level: 'INFO' });
        }
        if (headers.destination === '/app/queue/claim') {
          const source = queue.filter(item => item.enqueuedBy.token === body.sourceToken);
          const failure = rejectClaim || !config.allowPlaylistClaim;
          if (!failure) {
            owner.name = source[0].enqueuedBy.name;owner.isGuest = false;
            queue = queue.map(item => [body.sourceToken, owner.token].includes(item.enqueuedBy.token) ? { ...item, enqueuedBy: { ...owner } } : item);
            send('/user/queue/me', owner);
          }
          send('/user/queue/playlist/claim', { requestId: body.requestId, claimed: failure ? 0 : source.length, error: failure,
            message: failure ? '原主人在线或刚刚断线，请稍后再认领' : `已认领 ${source.length} 首，昵称已改为「${owner.name}」`, member: failure ? null : owner });
          broadcast();
        }
      }
    });
  });
  await page.goto('/', { waitUntil: 'commit' });await page.getByRole('button', { name: 'CONNECT', exact: true }).click();
  return { sent, updates, queue: () => queue };
}
async function loginAdmin(page) {await page.getByPlaceholder('ENTER ADMIN PASSWORD').fill('test-admin');await page.getByRole('button', { name: 'ACCESS SYSTEM' }).click();}

for (const mobile of [false, true]) test(`${mobile ? '320px mobile' : 'desktop'} guest claims and saves exact nickname without changing identity or other songs`, async ({ page }) => {
  if (mobile) await page.setViewportSize({ width: 320, height: 800 });
  const fixture = await room(page);if (mobile) await page.locator('#tutorial-queue-mobile').click();
  const claim = page.getByRole('button', { name: '认领 Original 的歌单并改名', exact: true });
  await expect(claim).toBeVisible();const bounds = await claim.boundingBox();expect(bounds.x).toBeGreaterThanOrEqual(0);if (mobile) expect(bounds.x + bounds.width).toBeLessThanOrEqual(320);
  const before = structuredClone(fixture.queue());await claim.click();
  await expect(page.getByText('已认领 3 首，昵称已改为「Original」', { exact: true })).toBeVisible();await expect(claim).toHaveCount(0);
  expect(fixture.queue().map(item => item.queueId)).toEqual(before.map(item => item.queueId));expect(fixture.queue().find(item => item.queueId === 'q4')).toEqual(before[4]);
  expect(fixture.queue().filter(item => item.enqueuedBy.token === 'current')).toHaveLength(4);
  expect(await page.evaluate(() => [localStorage.getItem('mp_username'), localStorage.getItem('mp_user_token')])).toEqual(['Original', 'current']);
  const request = fixture.sent.find(item => item.destination === '/app/queue/claim');expect(Object.keys(request.body).sort()).toEqual(['requestId', 'sourceToken']);expect(request.body.requestId).toBeTruthy();
  await page.reload({ waitUntil: 'commit' });await page.getByRole('button', { name: 'CONNECT', exact: true }).click();
  expect(await page.evaluate(() => localStorage.getItem('mp_username'))).toBe('Original');
});

test('rejection keeps playlist and permits retry', async ({ page }) => {
  const fixture = await room(page, { rejectClaim: true });const before = structuredClone(fixture.queue());
  const claim = page.getByRole('button', { name: '认领 Original 的歌单并改名', exact: true });await claim.click();
  await expect(page.getByText('原主人在线或刚刚断线，请稍后再认领', { exact: true })).toBeVisible();await expect(claim).toBeEnabled();expect(fixture.queue()).toEqual(before);
});
test('online owners and current identity have no claim button', async ({ page }) => {
  await room(page, { sourceOnline: true, guest: false });
  await expect(page.getByText('Original', { exact: true }).first()).toBeVisible();
  await expect(page.getByRole('button', { name: '认领 Original 的歌单并改名', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '认领 Other 的歌单并改名', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '认领 Current 的歌单并改名', exact: true })).toHaveCount(0);
});
test('disabled setting hides all recovery buttons', async ({ page }) => {
  await room(page, { enabled: false });await expect(page.getByText('Original', { exact: true }).first()).toBeVisible();
  await expect(page.getByRole('button', { name: /认领.*的歌单并改名/ })).toHaveCount(0);
});
test('admin switch persists partial settings and follows room state after reload', async ({ page }) => {
  const fixture = await room(page, { admin: true, guest: false, enabled: false });await loginAdmin(page);
  const control = page.getByRole('switch', { name: '允许认领离线成员歌单', exact: true });await expect(control).toHaveAttribute('aria-checked', 'false');await control.click();
  await expect(control).toHaveAttribute('aria-checked', 'true');expect(fixture.updates).toEqual([{ allowPlaylistClaim: true }]);
  await page.reload({ waitUntil: 'commit' });await page.getByRole('button', { name: 'CONNECT', exact: true }).click();
  await expect(control).toHaveAttribute('aria-checked', 'true');await control.click();await expect(control).toHaveAttribute('aria-checked', 'false');
  expect(fixture.updates).toEqual([{ allowPlaylistClaim: true }, { allowPlaylistClaim: false }]);
});
test('sequential mode exposes globally pinned-only orphan recovery', async ({ page }) => {
  const fixture = await room(page, { mode: 'SEQUENTIAL' });
  await page.getByRole('button', { name: '认领 Pinned 的歌单并改名', exact: true }).click();
  await expect(page.getByText('已认领 1 首，昵称已改为「Pinned」', { exact: true })).toBeVisible();
  expect(fixture.queue().find(item => item.queueId === 'q5').priority).toBe('GLOBAL_TOP');expect(fixture.queue()).toHaveLength(6);
});
test('failed admin save retains disabled state and shows error', async ({ page }) => {
  await room(page, { admin: true, guest: false, enabled: false, failSetting: true });await loginAdmin(page);
  const control = page.getByRole('switch', { name: '允许认领离线成员歌单', exact: true });await control.click();
  await expect(page.getByText('歌单认领设置保存失败', { exact: true })).toBeVisible();await expect(control).toHaveAttribute('aria-checked', 'false');
});
