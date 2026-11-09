const assert=require('node:assert/strict');
const path=require('node:path');
const {pathToFileURL}=require('node:url');

(async()=>{
 const {createRoomClock,createMediaSynchronizer}=await import(pathToFileURL(path.join(__dirname,'../src/services/playbackSync.js')));
 let now=0;
 const state=(position,{paused=false,id='1',url='audio.wav',duration=180000}={})=>({isPaused:paused,nowPlaying:{music:{id,platform:'netease',url,duration},currentPosition:position}});
 const clock=createRoomClock(()=>now);
 clock.sample(state(10000));
 now=5000;clock.sample(state(15000));
 now=8000;clock.sample(state(13000));assert.equal(clock.position(),18000);
 now=10000;clock.sample(state(20000));
 now=20000;clock.sample(state(21000));assert.equal(clock.position(),30000);
 now=22000;clock.sample(state(25000));assert.equal(clock.position(),32000);
 now=25000;clock.sample(state(35000));assert.equal(clock.position(),35000);
 now=45000;assert.equal(clock.position(),55000);assert.equal(clock.status().fresh,false);
 const revision=clock.status().revision;
 clock.expectReconnect();clock.sample(state(40000));
 assert.equal(clock.position(),40000);assert.equal(clock.status().revision,revision);
 now=46000;clock.sample(state(41000,{paused:true}));
 now=90000;clock.sample(state(41000,{paused:true}));assert.equal(clock.position(),41000);assert(clock.status().fresh);
 clock.sample(state(41000));now=92000;assert.equal(clock.position(),43000);
 clock.sample(state(55000,{duration:60000}));
 now=98000;clock.sample(state(1000,{duration:60000}));assert.equal(clock.position(),1000);assert.equal(clock.status().reason,'repeat');
 clock.sample(state(5000,{id:'2'}));assert.equal(clock.position(),5000);
 clock.sample({nowPlaying:null});assert.equal(clock.position(),0);
 console.log('PASS: late/older/duplicate frames cannot rewind room clock; stale state, reconnect, pause/resume, repeat and track changes handled');

 const input=(time,overrides={})=>({now:time,mediaTime:time/1000,targetTime:time/1000,
  ready:true,seeking:false,blocked:false,connected:true,fresh:true,paused:false,ended:false,buffering:false,
  revision:1,sampleId:Math.floor(time/5000)+1,sampleAt:Math.floor(time/5000)*5000,targetBuffered:true,bufferAhead:30,...overrides});
 const slew=createMediaSynchronizer();let media=0,seeks=0,rates=[];
 for(let t=0;t<=200000;t+=200){
  const result=slew.tick(input(t,{mediaTime:media,targetTime:t/1000+3}));
  // First-join alignment is a distinct explicit event. Introduce drift only after initialization.
  if(t===0){assert.equal(result.seek,3);media=3;}
  else if(result.seek!==undefined){seeks++;media=result.seek;}
  rates.push(result.rate);media+=.2*result.rate;
 }
 assert.equal(seeks,0);assert(rates.every(rate=>rate>=.98&&rate<=1.02));
 const gentle=createMediaSynchronizer();gentle.tick(input(0));media=0;seeks=0;
 for(let t=200;t<=200000;t+=200){
  const previous=media;
  const result=gentle.tick(input(t,{mediaTime:media,targetTime:t/1000+3}));
  assert.equal(result.seek,undefined);assert(result.rate>=.98&&result.rate<=1.02);
  media+=.2*result.rate;assert(media>=previous);
 }
 assert(Math.abs(media-203)<.8);
 console.log('PASS: small drift converges with pitch-preserving rate bounded to 0.98–1.02; no media rewinds or hard seeks');

 const badSample=createMediaSynchronizer();badSample.tick(input(0));
 for(let t=200;t<=14000;t+=200){
  const spike=t>=6000&&t<10000;
  assert.equal(badSample.tick(input(t,{targetTime:t/1000+(spike?12:0)})).seek,undefined);
 }
 const duplicates=createMediaSynchronizer();duplicates.tick(input(0));
 for(let t=5000;t<=60000;t+=200)assert.equal(duplicates.tick(input(t,{targetTime:t/1000+12,sampleId:2,sampleAt:5000})).seek,undefined);
 const large=createMediaSynchronizer();large.tick(input(0));let hard=0;
 for(let t=200;t<=45000;t+=200){
  const result=large.tick(input(t,{mediaTime:t/1000,targetTime:t/1000+(hard?0:12)}));
  if(result.seek!==undefined){assert(t>=15000);hard++;}
 }
 assert.equal(hard,1);
 const thin=createMediaSynchronizer();thin.tick(input(0));
 for(let t=200;t<=60000;t+=200){
  const result=thin.tick(input(t,{targetTime:t/1000+15,targetBuffered:false,bufferAhead:1}));
  assert.equal(result.seek,undefined);assert.equal(result.rate,1);
 }
 console.log('PASS: a bad sample and repeated ticks cannot trigger seeking; persistent large drift needs three independent samples; unbuffered targets never force a seek');

 for(const problem of [{buffering:true},{connected:false},{fresh:false},{seeking:true}]){
  const sync=createMediaSynchronizer();sync.tick(input(0));
  for(let t=1000;t<20000;t+=1000){const result=sync.tick(input(t,{targetTime:t/1000+20,...problem}));assert.equal(result.seek,undefined);assert.equal(result.rate,1);}
  for(let t=20000;t<25000;t+=200)assert.equal(sync.tick(input(t,{targetTime:t/1000+20})).seek,undefined);
 }
 const events=createMediaSynchronizer();events.tick(input(0));
 assert.equal(events.tick(input(60000,{mediaTime:60,targetTime:0,revision:2})).seek,0);
 assert.equal(events.tick(input(60200,{mediaTime:.2,targetTime:.2,revision:2})).seek,undefined);
 assert.equal(events.tick(input(61000,{mediaTime:1,targetTime:1.6,revision:3,paused:true})).seek,1.6);
 events.resetMedia();assert.equal(events.tick(input(62000,{mediaTime:0,targetTime:2.6,revision:3})).seek,2.6);
 console.log('PASS: buffering/offline/stale/seeking states suspend correction with recovery grace; initial join, repeat, pause and source recovery align once');
})().catch(error=>{console.error(error);process.exitCode=1;});
