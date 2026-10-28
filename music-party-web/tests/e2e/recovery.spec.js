import {test,expect} from '@playwright/test';
import {audioServer,room,media} from './room';
let audio;
test.beforeAll(async()=>{audio=await audioServer();});
test.afterAll(async()=>{await audio.close();});

test('expired audio recovers locally and autoplay unlock does not pause the room',async({page})=>{
  const hub=await room(page,{url:audio.url+'/expired.wav',refreshed:audio.url+'/tone.wav',blocked:true});
  await page.getByRole('button',{name:'点此开启声音',exact:true}).click();
  await expect.poll(async()=>{const a=await media(page);return a.decoded>0&&!a.paused&&a.time>5;}).toBe(true);
  expect(hub.refreshes()).toBeLessThanOrEqual(2);
  expect(await page.evaluate(()=>window.audioStats.controls)).toBe(0);expect(hub.errors).toEqual([]);
});
test('persistent failures stop at two refreshes and show explicit retry',async({page})=>{
  const hub=await room(page,{url:audio.url+'/expired.wav',failed:true});
  await page.getByRole('button',{name:'声音加载失败，点此重试',exact:true}).waitFor();
  expect(hub.refreshes()).toBe(2);await page.waitForTimeout(1800);expect(hub.refreshes()).toBe(2);
  expect((await media(page)).time).toBe(0);expect(await page.evaluate(()=>window.audioStats.controls)).toBe(0);
});
test('late refresh result is cancelled when a different track arrives',async({page})=>{
  const hub=await room(page,{url:audio.url+'/expired.wav',refreshed:audio.url+'/late.wav',refreshDelay:2000});
  await expect.poll(()=>hub.refreshes()).toBe(1);
  hub.push(10000,false,{id:'2',url:audio.url+'/new.wav'});
  await expect.poll(async()=>(await media(page)).src.endsWith('/new.wav')).toBe(true);
  await page.waitForTimeout(3000);
  expect((await media(page)).src.endsWith('/new.wav')).toBe(true);expect(hub.refreshes()).toBe(1);
  expect(hub.errors).toEqual([]);
});
test('nine-second download does not trigger a premature recovery',async({page})=>{
  const hub=await room(page,{url:audio.url+'/slow.wav'});
  await expect.poll(async()=>{const a=await media(page);return a.ready>=2&&!a.paused&&a.decoded>0;},{timeout:20000}).toBe(true);
  expect(hub.refreshes()).toBe(0);expect(hub.errors).toEqual([]);
});
