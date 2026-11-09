const {chromium,serve,tone:generateTone,launchOptions}=require('./support.cjs');
const assert = require('node:assert/strict');
const tone=generateTone();
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(check, timeout = 8000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await check()) return; await sleep(100); }
  throw new Error('Background playback condition timed out');
}
async function listener(browser, origin) {
  const context = await browser.newContext({viewport: {width: 393, height: 852}, isMobile: true, hasTouch: true});
  await context.addInitScript(() => {
    for (const [key, value] of Object.entries({mp_username:'BackgroundTester',mp_user_token:crypto.randomUUID(),mp_tutorial_done_v1:'true',mp_pwa_hint_shown:'true',mp_auto_lite_mode:'false',mp_volume:'0'})) localStorage.setItem(key, value);
    window.bg = {visibility: 'visible', block: '', attempts: 0, controls: 0, loads: 0, seeks: [], deferred: null};
    Object.defineProperty(document, 'visibilityState', {get: () => window.bg.visibility});
    window.visibility = value => { window.bg.visibility = value; document.dispatchEvent(new Event('visibilitychange')); };
    const original = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () {
      window.bg.attempts++;
      if (window.bg.block === 'defer') return new Promise((resolve, reject) => { window.bg.deferred = {resolve, reject}; });
      if (window.bg.block) return Promise.reject(new DOMException('Simulated mobile interruption', window.bg.block));
      return original.call(this);
    };
    const time = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'currentTime');
    Object.defineProperty(HTMLMediaElement.prototype, 'currentTime', {...time, set(value) { window.bg.seeks.push(value); time.set.call(this, value); }});
    const load = HTMLMediaElement.prototype.load;
    HTMLMediaElement.prototype.load = function () { window.bg.loads++; return load.call(this); };
    const send = WebSocket.prototype.send;
    WebSocket.prototype.send = function(frame) { if (String(frame).includes('/app/control/')) window.bg.controls++; return send.call(this, frame); };
    window.player = () => document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('player');
  });
  const page = await context.newPage();
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.route('https://**', route => route.abort());
  await page.route('**/api/**', route => {
    const url = new URL(route.request().url());
    const body = url.pathname === '/api/auth/status' ? {isSetup:true,hasProtection:false} : url.pathname.startsWith('/api/lyric/') ? '' : {};
    return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(body)});
  });
  await page.route('**/tone.wav*', route => {
    const match = /bytes=(\d+)-(\d*)/.exec(route.request().headers().range || '');
    const start = match ? Number(match[1]) : 0;
    const end = match?.[2] ? Math.min(Number(match[2]), tone.length - 1) : tone.length - 1;
    return route.fulfill({status:match?206:200,headers:{'Content-Type':'audio/wav','Accept-Ranges':'bytes',...(match?{'Content-Range':`bytes ${start}-${end}/${tone.length}`}:{})},body:tone.subarray(start,end+1)});
  });
  const room = {id:'first', start:Date.now(), paused:false, offset:10};
  let hub, connections = 0;
  await page.routeWebSocket('**/ws', socket => {
    connections++;
    const subscriptions = new Map(); let sequence = 0;
    const send = (destination, payload) => { for (const [id, topic] of subscriptions) if (topic === destination) socket.send(`MESSAGE\nsubscription:${id}\nmessage-id:${++sequence}\ndestination:${destination}\n\n${JSON.stringify(payload)}\0`); };
    hub = {push: () => send('/topic/player/state', {nowPlaying:{music:{id:room.id,name:room.id,artists:['Fixture'],duration:180000,platform:'netease',url:`${origin}/tone.wav?id=${room.id}`,coverUrl:''},currentPosition:(room.offset+(room.paused?0:(Date.now()-room.start)/1000))*1000,likeMarkers:[]},queue:[],onlineUsers:[],playMode:'SEQUENTIAL',isPaused:room.paused,config:{neteaseEnabled:true,bilibiliEnabled:false,privateDj:{mode:'OFF'}}})};
    socket.onMessage(raw => { for (const frame of String(raw).split('\0')) {
      const [head] = frame.split('\n\n'), lines = head.trim().split('\n'), command = lines.shift();
      const headers = Object.fromEntries(lines.map(line => { const i = line.indexOf(':'); return [line.slice(0,i),line.slice(i+1)]; }));
      if(command==='CONNECT') socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\nsession:fixture\n\n\0');
      if(command==='SUBSCRIBE') subscriptions.set(headers.id, headers.destination);
      if(command==='SEND' && headers.destination==='/app/player/resync') hub.push();
    }});
  });
  await page.goto(origin, {waitUntil:'domcontentloaded'});
  await page.getByRole('button', {name:'CONNECT',exact:true}).click();
  await until(() => page.locator('audio').evaluate(a => a.readyState>=2 && !a.paused && a.currentTime>5));
  return {context,page,errors,room,push:()=>hub.push(),connections:()=>connections};
}
const media = page => page.locator('audio').evaluate(a => ({time:a.currentTime,paused:a.paused,ready:a.readyState,decoded:a.webkitAudioDecodedByteCount}));
(async () => {
  const fixture=await serve();
  try {
  const browser = await chromium.launch(launchOptions());
  const client = await listener(browser, fixture.origin);
  try {
    const {page,room} = client;
    await page.evaluate(() => { window.visibility('hidden'); window.bg.block='AbortError'; });
    room.id='second'; room.start=Date.now(); room.offset=20; client.push();
    await until(() => page.locator('audio').evaluate(a => a.readyState>=1 && a.paused && a.currentSrc.includes('second')));
    await sleep(300);
    await page.evaluate(() => { window.bg.block=''; window.visibility('visible'); });
    await until(async () => { const a=await media(page); return !a.paused && a.time>15 && a.decoded>0; });
    console.log('PASS: background track interruption resumes actual decoding on foreground, re-aligns once, no room pause/skip');
    const before=await page.evaluate(()=>window.bg.seeks.length);
    for(let i=0;i<3;i++) await page.evaluate(()=>window.visibility('visible'));
    await sleep(1500);
    assert.equal(await page.evaluate(()=>window.bg.seeks.length),before);
    console.log('PASS: healthy playing foreground events cause no extra seeks or media reloads');
    await page.evaluate(()=>{window.visibility('hidden');window.bg.block='AbortError';});
    room.id='background-retry';room.start=Date.now();client.push();
    await until(()=>page.locator('audio').evaluate(a=>a.currentSrc.includes('background-retry') && a.readyState>=1 && a.paused));
    await page.evaluate(()=>{window.bg.block='';});
    await until(async()=>!(await media(page)).paused);
    console.log('PASS: transient background play abort retries locally without waiting for foreground');
    await page.evaluate(()=>window.visibility('visible'));
    await page.evaluate(()=>{window.visibility('hidden');window.bg.block='NotAllowedError';});
    room.id='third';room.start=Date.now();client.push();
    await page.getByRole('button',{name:'点此开启声音',exact:true}).waitFor();
    await page.evaluate(()=>window.visibility('visible'));
    await sleep(700);
    assert((await media(page)).paused);
    const attempts=await page.evaluate(()=>window.bg.attempts);
    await sleep(2000);
    assert.equal(await page.evaluate(()=>window.bg.attempts),attempts);
    await page.evaluate(()=>{window.bg.block='';});
    await page.getByRole('button',{name:'点此开启声音',exact:true}).click();
    await until(async()=>!(await media(page)).paused);
    console.log('PASS: autoplay denial offers bounded local unlock, no retry storm or shared control');
    await page.evaluate(()=>{window.bg.block='defer';document.querySelector('audio').pause();});
    await page.evaluate(()=>window.visibility('visible'));
    await until(()=>page.evaluate(()=>!!window.bg.deferred));
    room.id='fourth';room.start=Date.now();
    await page.evaluate(()=>{window.bg.block='';});client.push();
    await until(async()=>!(await media(page)).paused && await page.evaluate(()=>window.player().nowPlaying.music.id==='fourth'));
    await page.evaluate(()=>window.bg.deferred.reject(new DOMException('Old track denied','NotAllowedError')));
    await sleep(400);
    assert.equal(await page.getByRole('button',{name:'点此开启声音',exact:true}).count(),0);
    console.log('PASS: rejected play promise from prior track cannot block or mark current song failed');
    const connections=client.connections();
    await page.evaluate(()=>{window.restoreDateNow=Date.now;Date.now=()=>window.restoreDateNow()+25000;window.visibility('visible');});
    await until(()=>client.connections()>connections);
    await until(()=>page.evaluate(()=>window.player().connected));
    await page.evaluate(()=>{Date.now=window.restoreDateNow;});
    assert.equal(client.connections(),connections+1);
    console.log('PASS: stale socket marked connected is replaced once, preserving identity and local audio');
    room.paused=true;client.push();await until(async()=>(await media(page)).paused);
    await page.evaluate(()=>window.visibility('visible'));await sleep(800);
    assert((await media(page)).paused);
    assert.equal(await page.evaluate(()=>window.bg.controls),0);
    assert.deepEqual(client.errors,[]);
    console.log('PASS: explicit room pause stays paused through foreground recovery; no runtime errors');
  } finally { await client.context.close(); await browser.close(); }
  } finally {await fixture.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
