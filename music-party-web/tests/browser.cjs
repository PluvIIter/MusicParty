const {chromium,serve,tone,launchOptions}=require('./support.cjs');
const assert=require('node:assert/strict');
const audioBytes=tone();
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(check){const end=Date.now()+12000;while(Date.now()<end){if(await check())return;await sleep(50);}throw new Error('Compact sync browser condition timed out');}

async function run(browser,origin,{width=1280,legacy=false}={}){
 const context=await browser.newContext({viewport:{width,height:800}});
 await context.addInitScript(()=>{
  for(const [key,value]of Object.entries({mp_username:'Tester',mp_user_token:crypto.randomUUID(),mp_tutorial_done_v1:'true',mp_pwa_hint_shown:'true',mp_auto_lite_mode:'false',mp_volume:'0'}))localStorage.setItem(key,value);
  window.player=()=>document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('player');
 });
 const page=await context.newPage(),errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.route('https://**',r=>r.abort());
 await page.route('**/api/**',r=>{
  const pathname=new URL(r.request().url()).pathname;
  const body=pathname==='/api/auth/status'?{isSetup:true,hasProtection:false}:pathname.startsWith('/api/lyric/')?'':{};
  return r.fulfill({contentType:'application/json',body:JSON.stringify(body)});
 });
 await page.route('**/tone.wav*',r=>{
  const match=/bytes=(\d+)-(\d*)/.exec(r.request().headers().range||'');
  const start=match?Number(match[1]):0,end=match?.[2]?Math.min(Number(match[2]),audioBytes.length-1):audioBytes.length-1;
  return r.fulfill({status:match?206:200,headers:{'Content-Type':'audio/wav','Accept-Ranges':'bytes',...(match?{'Content-Range':`bytes ${start}-${end}/${audioBytes.length}`}:{})},body:audioBytes.subarray(start,end+1)});
 });
 const queue=Array.from({length:92},(_,i)=>({queueId:'queue-'+i,music:{id:String(i),name:'Song '+i,artists:['Artist'],duration:180000,platform:'netease',coverUrl:''},enqueuedBy:{token:'owner',name:'Owner',sessionId:'old',isGuest:false},status:'READY',priority:'REGULAR'}));
 let state={nowPlaying:{music:{...queue[0].music,url:origin+'/tone.wav'},currentPosition:10000,likeMarkers:[]},queue,onlineUsers:[],isPaused:false,isLoading:false,playMode:'SEQUENTIAL',config:{neteaseEnabled:true,bilibiliEnabled:false,maxUserSongs:100,privateDj:{mode:'OFF'}}};
 const {mergeState}=await import('../src/services/compactSync.js');
 let hub,sequence=0,connections=0,legacyResyncs=0,privateResyncs=0;
 const transmitted=[];
 await page.routeWebSocket('**/ws',socket=>{
  connections++;const subscriptions=new Map();let messageId=0;
  const send=(destination,body)=>{
   for(const [id,topic]of subscriptions)if(topic===destination){
    const text=`MESSAGE\nsubscription:${id}\nmessage-id:${++messageId}\ndestination:${destination}\n\n${JSON.stringify(body)}\0`;
    transmitted.push({destination,bytes:Buffer.byteLength(text),body});socket.send(text);
   }
  };
  const snapshot=destination=>send(destination,{v:1,epoch:'fixture',base:sequence,seq:sequence,full:true,state});
  hub={
   patch:(patch,{drop=false}={})=>{state=mergeState(state,patch);const base=sequence++;if(!drop)send('/topic/player/compact',{v:1,epoch:'fixture',base,seq:sequence,full:false,state:patch});},
   close:()=>socket.close()
  };
  socket.onMessage(raw=>{for(const frame of String(raw).split('\0')){
   const head=frame.split('\n\n')[0],lines=head.trim().split('\n'),command=lines.shift();
   const headers=Object.fromEntries(lines.map(line=>{const i=line.indexOf(':');return[line.slice(0,i),line.slice(i+1)];}));
   if(command==='CONNECT')socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\nsession:fixture\n\n\0');
   if(command==='SUBSCRIBE'){
    subscriptions.set(headers.id,headers.destination);
    if(headers.destination==='/app/user/me')send(headers.destination,{sessionId:'fixture',token:'tester',name:'Tester',isGuest:false});
    if(headers.destination==='/app/chat/history')send(headers.destination,[]);
    if(!legacy&&headers.destination==='/app/player/compact')snapshot(headers.destination);
   }
   if(command==='UNSUBSCRIBE')subscriptions.delete(headers.id);
   if(command==='SEND'&&headers.destination==='/app/player/resync'){legacyResyncs++;send('/topic/player/state',state);}
   if(command==='SEND'&&headers.destination==='/app/player/compact-resync'){privateResyncs++;snapshot('/user/queue/player/compact');}
  }});
 });
 try{
  await page.goto(origin,{waitUntil:'domcontentloaded'});
  await page.getByRole('button',{name:'CONNECT',exact:true}).click();
  await until(()=>page.evaluate(()=>window.player()?.queue.length===92));
  await until(()=>page.locator('audio').evaluate(a=>!a.paused&&a.webkitAudioDecodedByteCount>0));
  if(legacy){assert(legacyResyncs>0);assert.equal(transmitted.some(t=>t.body.v===1),false);assert.deepEqual(errors,[]);console.log('PASS: new client falls back to legacy server and decodes audio');return;}
  await sleep(500);assert.equal(legacyResyncs,0);
  const initial=transmitted.find(t=>t.body.full===true);
  const mark=transmitted.length;
  hub.patch({nowPlaying:{currentPosition:11000}});
  await until(()=>page.evaluate(()=>window.player().nowPlaying.currentPosition===11000));
  const heartbeat=transmitted.slice(mark).find(t=>t.body.full===false);
  assert(heartbeat.bytes<500);assert.equal(Object.hasOwn(heartbeat.body.state,'queue'),false);
  assert(heartbeat.bytes<initial.bytes*.02);
  assert.equal(await page.evaluate(()=>window.player().queue.length),92);
  hub.patch({config:{maxUserSongs:37}});
  await until(()=>page.evaluate(()=>window.player().config.maxUserSongs===37));
  assert.equal(await page.evaluate(()=>window.player().config.neteaseEnabled),true);
  hub.patch({queue:[...state.queue,{...queue[0],queueId:'added'}]});
  await until(()=>page.evaluate(()=>window.player().queue.length===93));
  hub.patch({isPaused:true});await until(()=>page.locator('audio').evaluate(a=>a.paused));
  hub.patch({isPaused:false});await until(()=>page.locator('audio').evaluate(a=>!a.paused));
  hub.patch({isSkipLocked:true},{drop:true});hub.patch({nowPlaying:{currentPosition:12000}});
  await until(()=>page.evaluate(()=>window.player().isSkipLocked));assert(privateResyncs>0);
  const beforeReconnect=connections;hub.close();
  await until(()=>connections>beforeReconnect);
  await until(()=>page.evaluate(()=>window.player().connected&&window.player().queue.length===93&&window.player().isSkipLocked));
  hub.patch({nowPlaying:null,queue:[],isPaused:true});
  await until(()=>page.evaluate(()=>window.player().nowPlaying===null&&window.player().queue.length===0));
  assert.equal(legacyResyncs,0);assert.deepEqual(errors,[]);
  console.log(`PASS: ${width}px compact snapshot, ${heartbeat.bytes}B heartbeat, sparse config/queue/transport, gap recovery, reconnect, explicit clear`);
 }finally{await context.close();}
}

(async()=>{
 const fixture=await serve();const browser=await chromium.launch(launchOptions());
 try{await run(browser,fixture.origin);await run(browser,fixture.origin,{width:320});await run(browser,fixture.origin,{legacy:true});}
 finally{await browser.close();await fixture.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
