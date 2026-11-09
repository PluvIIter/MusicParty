import { test, expect } from '@playwright/test';

// HTTP and STOMP fixtures keep browser tests independent of accounts and music services.
async function room(page, { failAlbumOnce = false, singleDelayMs = 0 } = {}) {
  const songs = [1, 2, 3, 4, 5].map(id => ({
    id: String(id), name: `Song ${id}`, artists: ['Artist'], duration: 180000,
    platform: 'netease', coverUrl: ''
  }));
  const album = { id: '900', name: 'Test album', artists: ['Artist'], coverUrl: '', trackCount: songs.length, songs };
  const owner = { token: 'test-member', sessionId: 'test-session', name: 'AlbumTester', isGuest: false };
  const queue = [];
  const sent = [];
  let fail = failAlbumOnce;
  const config = {
    neteaseEnabled: true, bilibiliEnabled: true, neteaseCookieConfigured: true,
    maxUserSongs: 100, maxQueueSize: 1000, maxPlaylistImportSize: 100,
    bilibiliMaxDurationMinutes: 10, privateDj: { mode: 'OFF' }
  };
  await page.addInitScript(() => {
    localStorage.setItem('mp_username', 'AlbumTester');
    localStorage.setItem('mp_user_token', 'test-member');
    localStorage.setItem('mp_tutorial_done_v1', 'true');
    localStorage.setItem('mp_pwa_hint_shown', 'true');
  });
  await page.route('https://**', route => route.abort());
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname;
    let body = {};
    if (path === '/api/auth/status') body = { isSetup: true, hasProtection: false };
    else if (path === '/api/config') body = { authorName: 'Test', backWords: 'MUSIC PARTY' };
    else if (path.startsWith('/api/search/bilibili/')) body = [
      { ...songs[0], id: 'BVshort', name: 'Short video', platform: 'bilibili' },
      { ...songs[1], id: 'BVlong', name: 'Long video', platform: 'bilibili', duration: 1200000 }
    ];
    else if (path.startsWith('/api/search/netease/')) body = songs.slice(0, 2);
    else if (path.startsWith('/api/album/')) {
      if (fail) {
        fail = false;
        return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ message: 'Album temporarily unavailable' }) });
      }
      body = album;
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  });
  await page.routeWebSocket('**/ws', socket => {
    const subscriptions = new Map();
    let sequence = 0;
    const send = (destination, payload) => {
      for (const [id, topic] of subscriptions) {
        if (topic === destination) socket.send(`MESSAGE\nsubscription:${id}\nmessage-id:${++sequence}\ndestination:${destination}\n\n${JSON.stringify(payload)}\0`);
      }
    };
    const state = () => ({ nowPlaying: null, queue, playMode: 'SEQUENTIAL', isPaused: false,
      isFairShuffle: true, allowOfflineShuffle: false, onlineUsers: [owner], config });
    const update = () => {
      send('/topic/player/queue', queue);
      send('/topic/player/state', state());
    };
    const add = song => queue.push({ queueId: `queue-${song.id}`, music: song, enqueuedBy: owner, status: 'READY', priority: 'REGULAR' });
    socket.onMessage(raw => {
      for (const frame of String(raw).split('\0')) {
        const [head, json] = frame.split('\n\n');
        const lines = head.trim().split('\n');
        const command = lines.shift();
        const headers = Object.fromEntries(lines.map(line => {
          const split = line.indexOf(':'); return [line.slice(0, split), line.slice(split + 1)];
        }));
        if (command === 'CONNECT') socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\nsession:test-session\n\n\0');
        if (command === 'SUBSCRIBE') {
          subscriptions.set(headers.id, headers.destination);
          if (headers.destination === '/app/user/me') send(headers.destination, owner);
          if (headers.destination === '/app/chat/history') send(headers.destination, []);
        }
        if (command !== 'SEND') continue;
        const body = JSON.parse(json || '{}');
        sent.push({ destination: headers.destination, body });
        if (headers.destination === '/app/player/resync') update();
        if (headers.destination === '/app/enqueue') {
          const song = songs.find(song => song.id === body.musicId);
          const enqueue = () => {
            if (song && !queue.some(item => item.music.id === song.id)) add(song);
            update();
          };
          if (singleDelayMs) setTimeout(enqueue, singleDelayMs);
          else enqueue();
        }
        if (headers.destination === '/app/enqueue/album') {
          let added = 0;
          for (const song of songs) {
            if (!queue.some(item => item.music.id === song.id)) { add(song); added++; }
          }
          update();
          const duplicates = songs.length - added;
          send('/user/queue/album/import', { requestId: body.requestId, albumId: album.id,
            total: songs.length, added, duplicates, limited: 0, error: false,
            message: `已添加 ${added} 首，重复跳过 ${duplicates} 首` });
        }
      }
    });
  });
  await page.goto('/', { waitUntil: 'commit' });
  await page.getByRole('button', { name: 'CONNECT', exact: true }).click();
  await page.locator('#tutorial-search').click();
  await page.getByPlaceholder('搜索音乐...').fill('test');
  await page.getByPlaceholder('搜索音乐...').press('Enter');
  await page.getByRole('button', { name: '查看《Song 1》所属专辑' }).waitFor();
  await expect.poll(() => sent.some(item => item.destination === '/app/player/resync')).toBe(true);
  return { queue, sent };
}

for (const viewport of [{ width: 1280, height: 900 }, { width: 320, height: 640 }]) {
  test(`single-song and album import remain independent at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    if (viewport.width === 320) {
      await page.addInitScript(() => {
        Object.defineProperty(globalThis.crypto, 'randomUUID', { value: undefined });
      });
    }
    const server = await room(page);
    const single = page.getByRole('button', { name: '添加单曲：Song 1', exact: true });
    const albumButton = page.getByRole('button', { name: '查看《Song 1》所属专辑', exact: true });
    await expect(single).toBeVisible();
    await expect(albumButton).toBeVisible();
    for (const button of [single, albumButton]) {
      const bounds = await button.boundingBox();
      expect(bounds.x).toBeGreaterThanOrEqual(0);
      expect(bounds.x + bounds.width).toBeLessThanOrEqual(viewport.width);
    }
    await single.click();
    await expect(page.getByRole('button', { name: '已在队列：Song 1', exact: true })).toBeDisabled();
    expect(server.queue.map(item => item.music.id)).toEqual(['1']);
    expect(server.sent.filter(item => item.destination === '/app/enqueue')).toHaveLength(1);

    await albumButton.click();
    const dialog = page.getByRole('dialog', { name: 'Test album' });
    await expect(dialog.getByRole('listitem')).toHaveCount(5);
    await expect(dialog.getByRole('button', { name: '已在队列：Song 1', exact: true })).toBeDisabled();
    const albumSingle = dialog.getByRole('button', { name: '添加单曲：Song 4', exact: true });
    await expect(albumSingle).toBeVisible();
    const singleBounds = await albumSingle.boundingBox();
    expect(singleBounds.x).toBeGreaterThanOrEqual(0);
    expect(singleBounds.x + singleBounds.width).toBeLessThanOrEqual(viewport.width);
    await albumSingle.click();
    await expect(dialog.getByRole('button', { name: '已在队列：Song 4', exact: true })).toBeDisabled();
    expect(server.queue.map(item => item.music.id)).toEqual(['1', '4']);
    expect(server.sent.filter(item => item.destination === '/app/enqueue')).toHaveLength(2);
    expect(server.sent.filter(item => item.destination === '/app/enqueue/album')).toHaveLength(0);
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: /添加整张专辑/ }).click();
    await expect(dialog.getByText('已添加 3 首，重复跳过 2 首')).toBeVisible();
    await expect(dialog.getByRole('button', { name: '全部已在队列' })).toBeDisabled();
    expect(server.queue.map(item => item.music.id)).toEqual(['1', '4', '2', '3', '5']);
    expect(server.sent.filter(item => item.destination === '/app/enqueue/album')).toHaveLength(1);
    expect(server.sent.find(item => item.destination === '/app/enqueue/album').body.requestId).toBeTruthy();
  });
}

test('album read error can be retried without adding songs', async ({ page }) => {
  const server = await room(page, { failAlbumOnce: true });
  await page.getByRole('button', { name: '查看《Song 1》所属专辑' }).click();
  await expect(page.getByRole('alert')).toContainText('Album temporarily unavailable');
  expect(server.queue).toHaveLength(0);
  await page.getByRole('button', { name: '重新读取' }).click();
  await expect(page.getByRole('dialog', { name: 'Test album' }).getByRole('listitem')).toHaveCount(5);
  expect(server.queue).toHaveLength(0);
});

test('Bilibili keeps its single-song action and duration limit', async ({ page }) => {
  await room(page);
  await page.getByRole('button', { name: /^bilibili$/i }).click();
  await expect(page.getByRole('button', { name: '添加单曲：Short video', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '添加单曲：Long video', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /所属专辑/ })).toHaveCount(0);
});

test('album single-song button blocks rapid repeats while queue result is pending', async ({ page }) => {
  const server = await room(page, { singleDelayMs: 600 });
  await page.getByRole('button', { name: '查看《Song 1》所属专辑' }).click();
  const dialog = page.getByRole('dialog', { name: 'Test album' });
  const single = dialog.getByRole('button', { name: '添加单曲：Song 3', exact: true });
  await single.evaluate(button => { button.click(); button.click(); });
  await expect(single).toBeDisabled();
  await expect(dialog.getByRole('button', { name: '已在队列：Song 3', exact: true })).toBeDisabled();
  expect(server.queue.map(item => item.music.id)).toEqual(['3']);
  expect(server.sent.filter(item => item.destination === '/app/enqueue')).toHaveLength(1);
  expect(server.sent.filter(item => item.destination === '/app/enqueue/album')).toHaveLength(0);
  await expect(dialog.getByRole('button', { name: /添加整张专辑/ })).toBeEnabled();
  await dialog.getByRole('button', { name: '关闭专辑' }).click();
  await expect(dialog).toHaveCount(0);
});
