'use strict'
// Actual built web app, real Java JAR and real Chromium, with synthetic upstream.
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),http=require('node:http');
const {spawn}=require('node:child_process');
const root=path.resolve(__dirname,'../..'),temp=fs.mkdtempSync(path.join(os.tmpdir(),'mp-web-v2-'));
const base='http://127.0.0.1:19148',room='fixture-web-room',admin='fixture-web-admin-32-characters';
const jar=process.env.MP_TEST_JAR||path.join(root,'target/MusicParty-1.4.1-fix.jar');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
let java,api,electron;
const results=[];
const check=(name,pass,actual)=>{results.push({name,pass,actual});console.log(JSON.stringify(results.at(-1)));if(!pass)process.exitCode=1};
async function main(){
 api=http.createServer((req,res)=>{const u=new URL(req.url,'http://localhost');res.setHeader('Content-Type','application/json');
 const song={id:123,name:'Web fixture',dt:120000,ar:[{name:'Fixture'}],al:{picUrl:''},fee:0};
 const body=u.pathname==='/search'?{code:200,result:{songs:[song]}}:u.pathname==='/song/detail'?{songs:[song]}:
 u.pathname.startsWith('/song/url')?{code:200,data:[{id:123,code:200,url:'https://example.invalid/test.mp3',time:30000,freeTrialInfo:{start:0,end:30}}]}:
 u.pathname==='/user/account'?{code:200,profile:{userId:123},account:{id:123}}:{code:200,lrc:{lyric:'[00:00.00]Fixture'}};
 res.end(JSON.stringify(body));});
 await new Promise(r=>api.listen(19149,'127.0.0.1',r));
 java=spawn(process.env.MP_TEST_JAVA||'C:\\Program Files\\Microsoft\\jdk-25.0.3.9-hotspot\\bin\\java.exe',['-jar',jar,'--server.address=127.0.0.1','--server.port=19148','--logging.level.root=WARN'],{cwd:temp,windowsHide:true,env:{...process.env,ADMIN_PASSWORD:admin,NETEASE_API_URL:'http://127.0.0.1:19149',NETEASE_COOKIE:'MUSIC_U=fixture',ROOM_REST_PROTECTION_ENABLED:'true',ROOM_TOKEN_TTL_SECONDS:'1'},stdio:['ignore','pipe','pipe']});
 let logs='';java.stdout.on('data',d=>logs+=d);java.stderr.on('data',d=>logs+=d);
 let ready=false;for(let i=0;i<80;i++){try{if((await fetch(base+'/api/config')).ok){ready=true;break}}catch{}await sleep(250)}
 if(!ready)throw Error('Java not ready '+logs.slice(-500));
 await fetch(base+'/api/auth/setup',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({password:room})});
 check('unauthenticated REST rejected',(await fetch(base+'/api/search/netease/fixture')).status===401);
 const probe=`(async()=>{const sleep=ms=>new Promise(r=>setTimeout(r,ms));let input=document.querySelector('input[type=password]');if(!input)throw Error('Missing built-web auth form');input.value=${JSON.stringify(room)};input.dispatchEvent(new Event('input',{bubbles:true}));await sleep(50);let button=[...document.querySelectorAll('button')].find(x=>/VERIFY|ENTER|UNLOCK|CONNECT|LOGIN/.test(x.textContent));if(!button)throw Error('Auth button not found');button.click();await sleep(300);const connectButton=[...document.querySelectorAll('button')].find(x=>x.textContent.trim()==='CONNECT');if(connectButton)connectButton.click();
 const stores=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s;const player=stores.get('player'),user=stores.get('user');for(let i=0;i<100&&!player.connected;i++)await sleep(50);await sleep(500);
 const initial={connected:player.connected,id:user.userToken,recoveryKey:user.recoveryKey};
 const renamed=await player.renameUser('WebAuditMember');await sleep(300);
 const nameOK=user.currentUser.name==='WebAuditMember'&&!user.isGuest;
 // Exercise protected REST through the real page axios layer via search UI.
 let search=[...document.querySelectorAll('button')].find(x=>/搜索|SEARCH/.test(x.textContent));if(search)search.click();await sleep(300);
 const inputs=[...document.querySelectorAll('input')];const q=inputs.find(x=>x.placeholder?.includes('搜索'));if(q){q.value='fixture';q.dispatchEvent(new Event('input',{bubbles:true}));q.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',bubbles:true}));await sleep(900)}
 const protectedSearchAfterExpiry=document.body.innerText.includes('Web fixture');player.syncState({nowPlaying:{music:{id:123,name:'Fixture',artists:['Test'],duration:120000,playableDurationMs:30000,trial:true,urlType:'TRIAL',url:''},currentPosition:0},queue:[],isPaused:true,onlineUsers:[{id:user.userToken,name:user.currentUser.name,isGuest:false},{id:'other-public',name:'Other',isGuest:false}]});await sleep(200);
 const identity={publicDiffersFromSecret:initial.id!==initial.recoveryKey,meResolved:user.resolveName(user.userToken)===user.currentUser.name,otherResolved:user.resolveName('other-public')==='Other',storedSecret:localStorage.getItem('mp_user_recovery_key')===initial.recoveryKey};
 const playback={duration:player.playableDuration,trialVisible:document.body.innerText.includes('试听')};
 const before=user.userToken;player.endSession();await player.connect();for(let i=0;i<60&&!player.connected;i++)await sleep(50);await sleep(400);const recovered=before===user.userToken;
 player.endSession();return {connected:initial.connected,nameOK,commandAck:renamed?.ok===true,protectedSearchAfterExpiry,identity,playback,recovered,cleaned:!player.nowPlaying&&!player.connected,bodyLength:document.body.innerText.length};})()`;
 const runner=path.join(temp,'runtime.cjs');const resultFile=path.join(temp,'result.json');
 fs.writeFileSync(runner,`const {app,BrowserWindow}=require('electron');const fs=require('node:fs');app.setPath('userData',${JSON.stringify(path.join(temp,'profile'))});app.whenReady().then(async()=>{const w=new BrowserWindow({show:false,webPreferences:{contextIsolation:true}});try{await w.loadURL(${JSON.stringify(base)});await new Promise(r=>setTimeout(r,600));const result=await w.webContents.executeJavaScript(${JSON.stringify(probe)},true);fs.writeFileSync(${JSON.stringify(resultFile)},JSON.stringify(result));app.exit(0)}catch(e){fs.writeFileSync(${JSON.stringify(resultFile)},JSON.stringify({error:e.stack}));app.exit(1)}})`);
 const electronExe=require(path.join('E:\\MusicPartyDesktop','node_modules/electron'));
 const env={...process.env};delete env.ELECTRON_RUN_AS_NODE;
 electron=spawn(electronExe,[runner],{env,windowsHide:true,stdio:['ignore','pipe','pipe']});let elog='';electron.stderr.on('data',d=>elog+=d);electron.stdout.on('data',d=>elog+=d);
 const exit=await new Promise(r=>{const t=setTimeout(()=>{electron.kill();r(-1)},40000);electron.on('exit',c=>{clearTimeout(t);r(c)})});
 const result=fs.existsSync(resultFile)?JSON.parse(fs.readFileSync(resultFile)):{};
 check('built-web boot and room authentication',exit===0&&result.connected,result);
 check('identity v2 and reconnect',result.nameOK&&result.recovered&&Object.values(result.identity||{}).every(Boolean),result.identity);
 check('trial UI uses playable duration',result.playback?.duration===30000&&result.playback?.trialVisible,result.playback);
 check('command ACK',result.commandAck===true);
 check('protected search renews expired room token',result.protectedSearchAfterExpiry===true);
 check('session cleanup',result.cleaned===true);
 if(exit!==0 || !result.connected)console.log(elog.slice(-2500));
}
main().catch(e=>{check('fatal',false,e.stack)}).finally(async()=>{if(electron?.exitCode===null)electron.kill();if(java?.exitCode===null)java.kill();if(api)await new Promise(r=>api.close(r));const out=path.join('E:\\MusicPartyDelivery','verification','web-rollout-runtime.json');fs.writeFileSync(out,JSON.stringify({jar,results},null,2));});
