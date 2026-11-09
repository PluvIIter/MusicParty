import { test, expect } from '@playwright/test';

// HTTP and STOMP fixtures keep browser tests independent of accounts and music services.
async function room(page, { failAlbumOnce = false, singleDelayMs = 0 } = {}) {
  let collectionMode = "ok"; const reads=[];
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
    else if (path === '/api/user/albums/netease') {
      reads.push(route.request());
      if (collectionMode === 'expired') return route.fulfill({ status:401,contentType:'application/json',body:JSON.stringify({message:'网易云登录已失效，请更新 Cookie'}) });
      const offset=Number(new URL(route.request().url()).searchParams.get('offset')||0);
      const card=id=>({id:String(id),name:'Saved '+id,artists:['Artist'],coverUrl:'',trackCount:5});
      body=collectionMode==='empty'?{items:[],total:0,hasMore:false,nextOffset:0}
        :{items:offset?[card(901),card(902)]:[card(900),card(901)],total:3,hasMore:offset===0,nextOffset:offset?4:2};
    }
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
  return { queue, sent, reads, setCollectionMode: mode => { collectionMode=mode; } };
}


for (const width of [1280,320]) test(`saved albums paginate and retain single/full add at ${width}px`,async({page})=>{
  await page.setViewportSize({width,height:800});
  await page.addInitScript(()=>localStorage.setItem('mp_room_password','fixture-room'));
  const server=await room(page);
  await page.getByRole('button',{name:'收藏专辑',exact:true}).click();
  const pane=page.getByRole('region',{name:'收藏专辑',exact:true});
  await expect(pane.getByRole('button',{name:/^查看专辑：/})).toHaveCount(2);
  await expect(pane.getByText(/与绑定的歌单用户 ID 无关/)).toBeVisible();
  await pane.getByRole('button',{name:'加载更多专辑',exact:true}).click();
  await expect(pane.getByRole('button',{name:/^查看专辑：/})).toHaveCount(3);
  await pane.getByRole('button',{name:'查看专辑：Saved 900',exact:true}).click();
  const dialog=page.getByRole('dialog',{name:'Test album'});
  await dialog.getByRole('button',{name:'添加单曲：Song 4',exact:true}).click();
  await expect(dialog.getByRole('button',{name:'已在队列：Song 4',exact:true})).toBeDisabled();
  expect(server.queue).toHaveLength(1);
  await dialog.getByRole('button',{name:/添加整张专辑/}).click();
  await expect(dialog.getByRole('button',{name:'全部已在队列'})).toBeDisabled();
  expect(server.queue).toHaveLength(5);
  expect(server.reads.every(r=>r.headers()['x-room-password']==='fixture-room')).toBe(true);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth)).toBe(false);
});
test('empty collection and login expiry support refresh and retry',async({page})=>{
  const server=await room(page);
  await page.getByRole('button',{name:'收藏专辑',exact:true}).click();
  const pane=page.getByRole('region',{name:'收藏专辑',exact:true});
  await pane.getByRole('button',{name:'查看专辑：Saved 900',exact:true}).waitFor();
  server.setCollectionMode('empty');await pane.getByRole('button',{name:'刷新收藏'}).click();
  await expect(pane.getByText(/当前账号还没有收藏专辑/)).toBeVisible();
  server.setCollectionMode('expired');await pane.getByRole('button',{name:'刷新收藏'}).click();
  await expect(pane.getByRole('alert')).toContainText('网易云登录已失效');
  server.setCollectionMode('ok');await pane.getByRole('button',{name:'重试读取收藏'}).click();
  await expect(pane.getByRole('button',{name:'查看专辑：Saved 900',exact:true})).toBeVisible();
  expect(server.queue).toHaveLength(0);
});
