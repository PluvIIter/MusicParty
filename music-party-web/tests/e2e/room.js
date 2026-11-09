import http from 'node:http';

export async function audioServer() {
  const samples = 180 * 8000, tone = Buffer.alloc(44 + samples * 2);
  tone.write('RIFF',0); tone.writeUInt32LE(tone.length-8,4); tone.write('WAVEfmt ',8);
  tone.writeUInt32LE(16,16);tone.writeUInt16LE(1,20);tone.writeUInt16LE(1,22);
  tone.writeUInt32LE(8000,24);tone.writeUInt32LE(16000,28);tone.writeUInt16LE(2,32);tone.writeUInt16LE(16,34);
  tone.write('data',36);tone.writeUInt32LE(samples*2,40);
  for(let i=0;i<samples;i++)tone.writeInt16LE(Math.round(Math.sin(i*2*Math.PI*220/8000)*500),44+i*2);
  const server = http.createServer((req,res) => {
    if(req.url.includes('expired') || req.url.includes('broken')) { res.writeHead(403);res.end();return; }
    const send=()=>{
      const range=req.headers.range?.match(/bytes=(\d+)-(\d*)/);
      const start=range?Number(range[1]):0,end=range&&range[2]?Math.min(Number(range[2]),tone.length-1):tone.length-1;
      res.writeHead(range?206:200,{'Content-Type':'audio/wav','Accept-Ranges':'bytes','Access-Control-Allow-Origin':'*',
        'Content-Length':end-start+1,...(range?{'Content-Range':`bytes ${start}-${end}/${tone.length}`}:{})});
      res.end(tone.subarray(start,end+1));
    };
    if(req.url.includes('slow'))setTimeout(send,9000);else send();
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  return {url:`http://127.0.0.1:${server.address().port}`,close:()=>new Promise(resolve=>server.close(resolve))};
}

export async function room(page, {url, refreshed=url, blocked=false, failed=false, refreshDelay=0}={}) {
  await page.addInitScript(({blocked})=>{
    localStorage.setItem('mp_username','AudioTester');localStorage.setItem('mp_user_token',crypto.randomUUID());
    localStorage.setItem('mp_room_password','fixture-room');localStorage.setItem('mp_tutorial_done_v1','true');localStorage.setItem('mp_pwa_hint_shown','true');
    localStorage.setItem('mp_auto_lite_mode','false');localStorage.setItem('mp_volume','0');
    window.audioStats={seeks:[],rates:[],controls:0};window.audioAllowed=!blocked;
    const descriptor=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'currentTime');
    Object.defineProperty(HTMLMediaElement.prototype,'currentTime',{...descriptor,set(value){window.audioStats.seeks.push({from:descriptor.get.call(this),to:value});descriptor.set.call(this,value);}});
    window.injectMediaTime=value=>descriptor.set.call(document.querySelector('audio'),value);
    const rate=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'playbackRate');
    Object.defineProperty(HTMLMediaElement.prototype,'playbackRate',{...rate,set(value){window.audioStats.rates.push(value);rate.set.call(this,value);}});
    const play=HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play=function(){return window.audioAllowed?play.call(this):Promise.reject(new DOMException('Fixture autoplay block','NotAllowedError'));};
    document.addEventListener('click',e=>{if(e.target.textContent.includes('点此开启声音'))window.audioAllowed=true;},true);
    const send=WebSocket.prototype.send;
    WebSocket.prototype.send=function(frame){if(String(frame).includes('/app/control/'))window.audioStats.controls++;return send.call(this,frame);};
  },{blocked});
  await page.route('https://**',route=>route.abort());
  let refreshes=0,hub,started=performance.now();
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  await page.route('**/api/**',async route=>{
    const path=new URL(route.request().url()).pathname;let body={};
    if(path==='/api/auth/status')body={isSetup:true,hasProtection:false};
    else if(path==='/api/config')body={authorName:'Fixture'};
    else if(path.includes('/api/music/refresh/')){
      refreshes++;
      if(route.request().headers()['x-room-password']!=='fixture-room')throw new Error('Missing room password');
      if(refreshDelay)await new Promise(r=>setTimeout(r,refreshDelay));
      if(failed)return route.fulfill({status:503,contentType:'application/json',body:'{}'}).catch(()=>{});
      body={id:'1',platform:'netease',url:refreshed};
    }
    await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(body)}).catch(()=>{});
  });
  await page.routeWebSocket('**/ws',socket=>{
    const subscriptions=new Map();let seq=0;
    const send=(destination,body)=>{for(const[id,topic]of subscriptions)if(topic===destination)socket.send(`MESSAGE\nsubscription:${id}\nmessage-id:${++seq}\ndestination:${destination}\n\n${JSON.stringify(body)}\0`);};
    hub={push:(position=10000+performance.now()-started,paused=false,music={})=>send('/topic/player/state',{
      nowPlaying:{music:{id:'1',platform:'netease',name:'Audio fixture',artists:['Fixture'],duration:180000,url,coverUrl:'',...music},currentPosition:position,likeMarkers:[]},
      queue:[],onlineUsers:[],playMode:'SEQUENTIAL',isPaused:paused,isFairShuffle:true,config:{neteaseEnabled:true,bilibiliEnabled:false,privateDj:{mode:'OFF'}}
    })};
    socket.onMessage(raw=>{for(const frame of String(raw).split('\0')){
      const[head]=frame.split('\n\n');const lines=head.trim().split('\n'),command=lines.shift();
      const headers=Object.fromEntries(lines.map(line=>{const i=line.indexOf(':');return[line.slice(0,i),line.slice(i+1)];}));
      if(command==='CONNECT')socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\nsession:fixture\n\n\0');
      if(command==='SUBSCRIBE'){
        subscriptions.set(headers.id,headers.destination);
        if(headers.destination==='/app/user/me')send(headers.destination,{sessionId:'fixture',token:'fixture',name:'AudioTester',isGuest:false});
        if(headers.destination==='/app/chat/history')send(headers.destination,[]);
      }
      if(command==='SEND'&&headers.destination==='/app/player/resync')hub.push();
    }});
  });
  await page.goto('/',{waitUntil:'commit'});
  await page.getByRole('button',{name:'CONNECT',exact:true}).click();
  return {push:(...args)=>hub.push(...args),refreshes:()=>refreshes,errors};
}

export const media=page=>page.locator('audio').evaluate(a=>({time:a.currentTime,paused:a.paused,ready:a.readyState,decoded:a.webkitAudioDecodedByteCount,rate:a.playbackRate,src:a.currentSrc}));
