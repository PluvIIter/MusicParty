import {test,expect} from '@playwright/test';
import {audioServer,room,media} from './room';
let audio;
test.beforeAll(async()=>{audio=await audioServer();});
test.afterAll(async()=>{await audio.close();});

for(const width of [1280,320])test(`late ordered state packets do not interrupt decoded playback at ${width}px`,async({page})=>{
  await page.setViewportSize({width,height:800});
  const hub=await room(page,{url:audio.url+'/tone.wav'});
  await expect.poll(async()=>{const a=await media(page);return a.ready>=2&&!a.paused&&a.time>5;}).toBe(true);
  await expect.poll(()=>page.evaluate(()=>Math.abs(document.querySelector('audio').currentTime-document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('player').localProgress/1000))).toBeLessThan(.75);
  await page.evaluate(()=>{window.audioStats.seeks=[];});
  const started=performance.now();
  const initial=(await media(page)).time;
  const packets=[[0,0],[2,2],[4,4],[12,6],[14,8],[16,10],[16.4,12],[16.8,14],[17.2,16],[18,18],[20,20],[22,22]];
  let index=0,previous=initial;
  while(performance.now()-started<23000){
    const elapsed=(performance.now()-started)/1000;
    while(index<packets.length&&elapsed>=packets[index][0]){hub.push((initial+packets[index][1])*1000);index++;}
    const a=await media(page);expect(a.time).toBeGreaterThanOrEqual(previous-.05);previous=a.time;
    const difference=await page.evaluate(()=>Math.abs(document.querySelector('audio').currentTime-document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('player').localProgress/1000));
    expect(difference).toBeLessThan(1);
    await page.waitForTimeout(150);
  }
  const stats=await page.evaluate(()=>window.audioStats);
  expect(stats.seeks).toHaveLength(0);expect(stats.controls).toBe(0);
  expect(stats.rates.every(rate=>rate>=.98&&rate<=1.02)).toBe(true);
  expect((await media(page)).decoded).toBeGreaterThan(0);expect(hub.errors).toEqual([]);
});

test('short buffering settles without seek loops; room pause and resume still align',async({page})=>{
  const hub=await room(page,{url:audio.url+'/tone.wav'});
  await expect.poll(async()=>{const a=await media(page);return a.ready>=2&&!a.paused&&a.time>5;}).toBe(true);
  await page.evaluate(()=>{window.audioStats.seeks=[];const a=document.querySelector('audio');a.pause();a.dispatchEvent(new Event('waiting'));});
  const start=performance.now(),base=(await media(page)).time;
  for(let i=0;i<4;i++){hub.push((base+(performance.now()-start)/1000)*1000);await page.waitForTimeout(1000);}
  await page.locator('audio').evaluate(async a=>{await a.play();a.dispatchEvent(new Event('canplay'));a.dispatchEvent(new Event('seeked'));});
  for(let i=0;i<6;i++){hub.push((base+(performance.now()-start)/1000)*1000);await page.waitForTimeout(1000);}
  expect(await page.evaluate(()=>window.audioStats.seeks)).toHaveLength(0);
  hub.push(25000,true);await expect.poll(async()=>(await media(page)).paused).toBe(true);
  hub.push(25000,false);await expect.poll(async()=>!(await media(page)).paused).toBe(true);
  expect((await media(page)).time).toBeGreaterThanOrEqual(24.5);
  expect(await page.evaluate(()=>window.audioStats.controls)).toBe(0);
});
