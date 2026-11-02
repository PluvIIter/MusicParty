const {chromium,serve,tone:generateTone,launchOptions}=require('./support.cjs');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const tone=generateTone();
const CHUNK=512*1024;
let origin;
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(check,timeout=15000){const end=Date.now()+timeout;while(Date.now()<end){if(await check())return;await sleep(100);}throw new Error('Audio cache condition timed out');}
const source=(file='0123456789abcdef0123456789abcdef.wav',sign='one',host='m801')=>`https://${host}.music.126.net/rotating/${sign}/${file}?sign=${sign}`;
const cached=(url,id='101')=>`${origin}/__mp_audio_cache/v1/${id}?source=${encodeURIComponent(url)}`;
async function bytes(page,url,range){return page.evaluate(async({url,range})=>{const r=await fetch(url,{headers:range?{Range:range}:{}});const data=new Uint8Array(await r.arrayBuffer());return {status:r.status,range:r.headers.get('Content-Range'),data:Array.from(data)};},{url,range});}
async function cacheStats(page){return page.evaluate(()=>new Promise(resolve=>{const c=new MessageChannel();c.port1.onmessage=e=>resolve(e.data);navigator.serviceWorker.controller.postMessage({type:'mp-audio-cache',action:'stats'},[c.port2]);}));}
(async()=>{
 const fixture=await serve();origin=fixture.origin;
 try {
 const browser=await chromium.launch(launchOptions());
 const context=await browser.newContext({viewport:{width:393,height:852},isMobile:true,hasTouch:true});
 const failures=[],requests=[];
 await context.addInitScript(()=>{
  for(const [key,value]of Object.entries({mp_username:'CacheTester',mp_tutorial_done_v1:'true',mp_pwa_hint_shown:'true',mp_auto_lite_mode:'false',mp_volume:'0'}))localStorage.setItem(key,value);
  if(!localStorage.getItem('mp_user_token'))localStorage.setItem('mp_user_token',crypto.randomUUID());
  window.cacheControls=0;
  window.cacheVisibility='visible';window.cachePlayAllowed=true;
  Object.defineProperty(document,'visibilityState',{get:()=>window.cacheVisibility});
  window.setCacheVisibility=value=>{window.cacheVisibility=value;document.dispatchEvent(new Event('visibilitychange'));};
  const play=HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play=function(){return window.cachePlayAllowed?play.call(this):Promise.reject(new DOMException('Fixture background interruption','AbortError'));};
  const send=WebSocket.prototype.send;
  WebSocket.prototype.send=function(frame){if(String(frame).includes('/app/control/'))window.cacheControls++;return send.call(this,frame);};
 });
 await context.route('https://**',route=>route.abort());
 await context.route('https://*.music.126.net/**',async route=>{
  const name=new URL(route.request().url()).pathname.split('/').pop();
  const match=/^bytes=(\d+)-(\d*)$/.exec(route.request().headers().range||'');
  const start=match?Number(match[1]):0,end=match&&match[2]?Math.min(Number(match[2]),tone.length-1):tone.length-1;
  const status=name.startsWith('broken')?403:match?206:200;
  const body=status===403?Buffer.from('Forbidden'):tone.subarray(start,end+1);
  requests.push({name,start,end,size:body.length,status});
  await route.fulfill({status,headers:{'Content-Type':'audio/wav','Accept-Ranges':'bytes','Access-Control-Allow-Origin':'*','Access-Control-Expose-Headers':'Content-Range,Content-Length','Cache-Control':'no-store',...(status===206?{'Content-Range':`bytes ${start}-${end}/${tone.length}`}:{})},body}).catch(()=>{});
 });
 const page=await context.newPage();page.on('pageerror',error=>failures.push(error.message));
 await page.route('**/api/**',route=>{const name=new URL(route.request().url()).pathname;const body=name==='/api/auth/status'?{isSetup:true,hasProtection:false}:name.startsWith('/api/lyric/')?'':{};return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(body)});});
 const room={url:source(),started:Date.now(),paused:false};let hub;
 await page.routeWebSocket('**/ws',socket=>{
  const subscriptions=new Map();let sequence=0;
  const send=(destination,payload)=>{for(const[id,topic]of subscriptions)if(topic===destination)socket.send(`MESSAGE\nsubscription:${id}\nmessage-id:${++sequence}\ndestination:${destination}\n\n${JSON.stringify(payload)}\0`);};
  hub={push:()=>send('/topic/player/state',{nowPlaying:{music:{id:'101',name:'Cache fixture',artists:['Fixture'],duration:180000,platform:'netease',url:room.url,coverUrl:''},currentPosition:10000+(Date.now()-room.started),likeMarkers:[]},queue:[],onlineUsers:[],playMode:'SEQUENTIAL',isPaused:room.paused,config:{neteaseEnabled:true,bilibiliEnabled:false,privateDj:{mode:'OFF'}}})};
  socket.onMessage(raw=>{for(const frame of String(raw).split('\0')){const[head]=frame.split('\n\n'),lines=head.trim().split('\n'),command=lines.shift();const headers=Object.fromEntries(lines.map(line=>{const i=line.indexOf(':');return[line.slice(0,i),line.slice(i+1)];}));
   if(command==='CONNECT')socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\nsession:fixture\n\n\0');
   if(command==='SUBSCRIBE')subscriptions.set(headers.id,headers.destination);
   if(command==='SEND'&&headers.destination==='/app/player/resync')hub.push();
  }});
 });
 const transferred=()=>requests.reduce((sum,item)=>sum+item.size,0);
 try{
  await page.goto(origin,{waitUntil:'domcontentloaded'});
  await page.getByRole('button',{name:'CONNECT',exact:true}).click();
  await until(()=>page.locator('audio').evaluate(a=>a.currentTime>5&&!a.paused&&a.webkitAudioDecodedByteCount>0));
  assert((await page.locator('audio').getAttribute('src')).startsWith('/__mp_audio_cache/'));
  await page.evaluate(async url=>{const r=await fetch(url);window.fullAudioBytes=(await r.arrayBuffer()).byteLength;},cached(room.url));
  assert.equal(await page.evaluate(()=>window.fullAudioBytes),tone.length);
  const firstBytes=transferred();
  assert(firstBytes<=tone.length+CHUNK*2,JSON.stringify({firstBytes,total:tone.length,requests}));
  assert.equal((await cacheStats(page)).bytes,tone.length);
  console.log('PASS: actual audio decodes through chunk cache; complete first pass transfers at most one file plus bounded read-ahead, not separate playback and cache downloads');
  room.url=source(undefined,'two','m701');hub.push();
  await until(()=>page.locator('audio').evaluate(a=>!a.paused&&a.currentTime>5&&a.currentSrc.includes('two')));
  const selected=await bytes(page,cached(room.url),'bytes=12345-13368');
  assert.equal(selected.status,206);assert.deepEqual(Buffer.from(selected.data),tone.subarray(12345,13369));
  assert.equal(transferred(),firstBytes);
  console.log('PASS: rotating CDN directory, signature and host reuse same audio bytes; replay and seek add zero CDN bytes');
  const token=await page.evaluate(()=>localStorage.getItem('mp_user_token'));
  await page.reload({waitUntil:'domcontentloaded'});await page.getByRole('button',{name:'CONNECT',exact:true}).click();
  await until(()=>page.locator('audio').evaluate(a=>!a.paused&&a.currentTime>5));
  assert.equal(transferred(),firstBytes);
  assert.equal(await page.evaluate(()=>localStorage.getItem('mp_user_token')),token);
  console.log('PASS: cache survives page reload with identity intact and no second audio download');
  await page.evaluate(async()=>{await(await caches.open('fixture-unrelated')).put('/unrelated',new Response('keep'));});
  await page.locator('#tutorial-rename-mobile').click();
  await page.getByRole('button',{name:'音乐缓存',exact:true}).click();
  const dialog=page.getByRole('dialog',{name:'音乐缓存',exact:true});
  await page.setViewportSize({width:320,height:780});
  await dialog.getByRole('button',{name:'清除音乐缓存',exact:true}).waitFor();
  fs.mkdirSync(path.join(__dirname,'../test-results'),{recursive:true});
  await page.screenshot({path:path.join(__dirname,'../test-results/cache-mobile.png')});
  assert(await dialog.evaluate(element=>element.getBoundingClientRect().right<=window.innerWidth));
  await dialog.getByRole('button',{name:'清除音乐缓存',exact:true}).click();
  await dialog.getByText('音乐缓存已清除',{exact:true}).waitFor();
  assert.equal((await cacheStats(page)).bytes,0);
  assert.equal(await page.evaluate(()=>localStorage.getItem('mp_user_token')),token);
  assert(await page.evaluate(async()=>!!await(await caches.open('fixture-unrelated')).match('/unrelated')));
  const beforeTime=await page.locator('audio').evaluate(a=>a.currentTime);await sleep(500);
  assert(await page.locator('audio').evaluate((a,t)=>!a.paused&&a.currentTime>t,beforeTime));
  await dialog.getByRole('button',{name:'关闭音乐缓存设置'}).click();
  console.log('PASS: 393px cache UI clears only local music bytes; identity, unrelated storage and ongoing playback survive');
  await page.locator('audio').evaluate(a=>a.pause());
  const range=await bytes(page,cached(room.url),'bytes=32-255');assert.deepEqual(Buffer.from(range.data),tone.subarray(32,256));
  const beforeSuffix=transferred();
  const suffix=await bytes(page,cached(room.url),'bytes=-257');assert.deepEqual(Buffer.from(suffix.data),tone.subarray(-257));assert(transferred()-beforeSuffix<=CHUNK);
  const beforeInvalid=transferred();assert.equal((await bytes(page,cached(room.url),`bytes=${tone.length}-`)).status,416);assert.equal((await bytes(page,cached(room.url),'bytes=0-1,3-4')).status,400);assert.equal(transferred(),beforeInvalid);
  console.log('PASS: byte-exact partial and suffix reads, bounded chunk downloads, valid 416 and rejection of malformed multi-range requests');
  const quality=source('fedcba9876543210fedcba9876543210.wav','quality');
  const beforeQuality=transferred();await bytes(page,cached(quality),'bytes=0-99');assert.equal(transferred()-beforeQuality,CHUNK);
  console.log('PASS: different encoded file/quality never collides with an existing song cache');
  const beforeBroken=await cacheStats(page);const broken=await bytes(page,cached(source('broken00000000000000000000000000.wav')),'bytes=0-99');assert.equal(broken.status,403);assert.deepEqual(await cacheStats(page),beforeBroken);
  console.log('PASS: failed media responses are not cached; original upstream failure remains available to existing bounded recovery');
  const concurrent=source('cccccccccccccccccccccccccccccccc.wav','concurrent');
  const concurrentBytes=()=>requests.filter(r=>r.name==='cccccccccccccccccccccccccccccccc.wav').reduce((n,r)=>n+r.size,0);const beforeConcurrent=concurrentBytes();await Promise.all([bytes(page,cached(concurrent),'bytes=0-4095'),bytes(page,cached(concurrent.replace('sign=concurrent','sign=second')),'bytes=0-4095')]);assert.equal(concurrentBytes()-beforeConcurrent,CHUNK);
  console.log('PASS: concurrent readers of one uncached chunk share one CDN request');
  const worker=context.serviceWorkers()[0];
  await worker.evaluate(()=>{const put=Cache.prototype.put;let armed=true;Cache.prototype.put=function(request,response){const url=typeof request==='string'?request:request.url;if(armed&&url.includes('/__mp_audio_store/')&&!url.endsWith('/meta')){armed=false;return Promise.reject(new DOMException('Fixture full quota','QuotaExceededError'));}return put.call(this,request,response);};});
  const quota=source('dddddddddddddddddddddddddddddddd.wav','quota');const quotaResponse=await bytes(page,cached(quota),'bytes=0-999');assert.deepEqual(Buffer.from(quotaResponse.data),tone.subarray(0,1000));assert.equal((await cacheStats(page)).files,1);
  assert.equal(await page.evaluate(()=>window.cacheControls),0);assert.deepEqual(failures,[]);
  console.log('PASS: quota pressure evicts old cached songs, retries cache write and keeps playback bytes valid; no room controls or runtime errors');
 }finally{await context.close();await browser.close();}
 }finally{await fixture.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
