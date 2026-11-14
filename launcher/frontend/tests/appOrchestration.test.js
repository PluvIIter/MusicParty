import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import * as tunnelHelpers from '../src/tunnelStatus.js';
import * as attemptHelpers from '../src/serviceAttempt.js';
import * as sourceHelpers from '../src/sourceSettings.js';

const script = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
  .match(/<script setup>([\s\S]*?)<\/script>/)[1]
  .replace(/^import .*;\n/gm, '');
const url = 'https://quiet-blue-fish.trycloudflare.com';
const newUrl = 'https://new-blue-fish.trycloudflare.com';
const ready = { enabled: true, state: 'ready', url, error: '' };
const failed = { enabled: true, state: 'failed', url, error: 'required service exited' };
const online = { JAVA_SERVER: true, NETEASE_API: true, QQMUSIC_API: true };
const offline = { JAVA_SERVER: false, NETEASE_API: false, QQMUSIC_API: false };
const flush = () => new Promise(resolve => setImmediate(resolve));

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

async function createApp(config = {}) {
  const calls = { save: [], start: 0, stop: 0 };
  const replies = {
    save: () => Promise.resolve(),
    start: () => Promise.resolve(),
    stop: () => Promise.resolve(),
    statuses: () => Promise.resolve(online),
    tunnel: () => Promise.resolve(ready),
  };
  let mount;
  let unmount;
  const context = vm.createContext({
    ...tunnelHelpers, ...attemptHelpers, ...sourceHelpers,
    ref: value => ({ value }),
    reactive: value => value,
    computed: getter => ({ get value() { return getter(); } }),
    onMounted: callback => { mount = callback; },
    onUnmounted: callback => { unmount = callback; },
    LoadConfig: async () => ({ cloudflareTunnelEnabled: true, ...config }),
    GetSourceDescriptors: async () => [],
    SaveConfig: value => { calls.save.push(value); return replies.save(); },
    StartServices: () => { calls.start++; return replies.start(); },
    StopServices: () => { calls.stop++; return replies.stop(); },
    GetServiceStatuses: () => replies.statuses(),
    GetTunnelStatus: () => replies.tunnel(),
    OpenBrowser: async () => {},
    EventsOn: () => () => {},
    setInterval: () => 1,
    clearInterval: () => {},
    setTimeout: () => {},
  });
  vm.runInContext(`${script}\nglobalThis.app = {
    config, configReady, configLocked, passwordWarning, isStarting, isStopping, isRunning,
    isJavaReady, isApiReady, serviceStatuses, tunnelStatus, publicUrl, tunnelActionMessage,
    errorMessage, pollStatuses, toggleServices,
  };`, context, { filename: 'App.vue' });
  await mount();
  return { app: context.app, replies, calls, unmount };
}

async function start(fixture) {
  await fixture.app.toggleServices();
  await flush();
}

function deferPoll(fixture) {
  const statuses = deferred();
  const tunnel = deferred();
  fixture.replies.statuses = () => statuses.promise;
  fixture.replies.tunnel = () => tunnel.promise;
  return { statuses, tunnel, pending: fixture.app.pollStatuses() };
}

function assertFailed(app, error = failed.error) {
  assert.equal(app.tunnelStatus.value.state, 'failed');
  assert.equal(app.tunnelStatus.value.error, error);
  assert.equal(app.tunnelStatus.value.url, '');
  assert.equal(app.publicUrl.value, '');
}

for (const first of ['statuses', 'tunnel']) {
  test(`required-process failure retains terminal diagnostic with ${first} reply first`, async () => {
    const fixture = await createApp();
    await start(fixture);
    const { app, replies } = fixture;
    assert.equal(app.publicUrl.value, url);
    app.tunnelActionMessage.value = 'copied';
    app.isJavaReady.value = true;
    app.isApiReady.value = true;
    const poll = deferPoll(fixture);
    if (first === 'statuses') poll.statuses.resolve(offline);
    else poll.tunnel.resolve(failed);
    await flush();
    assert.equal(app.publicUrl.value, '');
    if (first === 'statuses') {
      assert.equal(app.isRunning.value, false);
      assert.equal(app.configLocked.value, false);
    }
    if (first === 'statuses') poll.tunnel.resolve(failed);
    else poll.statuses.resolve(offline);
    await poll.pending;
    assertFailed(app);
    assert.equal(app.tunnelActionMessage.value, '');
    assert.equal(app.isRunning.value, false);
    assert.equal(app.isJavaReady.value, false);
    assert.equal(app.isApiReady.value, false);
    assert.equal(app.configLocked.value, false);
    app.config.cloudflareTunnelEnabled = false;
    replies.statuses = async () => offline;
    for (const state of ['ready', 'starting', 'off', 'stopped']) {
      replies.tunnel = async () => ({ ...ready, state });
      await app.pollStatuses();
      assertFailed(app);
    }
  });
}

test('running false suppresses stale URL but keeps polling for a later terminal state', async () => {
  const fixture = await createApp();
  await start(fixture);
  const { app, replies } = fixture;
  const poll = deferPoll(fixture);
  poll.statuses.resolve(offline);
  await flush();
  assert.equal(app.publicUrl.value, '');
  assert.equal(app.tunnelStatus.value.url, '');
  poll.tunnel.resolve(ready);
  await poll.pending;
  assert.equal(app.publicUrl.value, '');
  assert.equal(app.tunnelStatus.value.url, '');
  replies.statuses = async () => offline;
  replies.tunnel = async () => failed;
  await app.pollStatuses();
  assertFailed(app);
});

for (const first of ['statuses', 'tunnel']) {
  test(`tunnel read rejection survives process failure with ${first} reply first`, async () => {
    const fixture = await createApp();
    await start(fixture);
    const poll = deferPoll(fixture);
    if (first === 'statuses') poll.statuses.resolve(offline);
    else poll.tunnel.reject(new Error('GetTunnelStatus unavailable'));
    await flush();
    if (first === 'statuses') poll.tunnel.reject(new Error('GetTunnelStatus unavailable'));
    else poll.statuses.resolve(offline);
    await poll.pending;
    assertFailed(fixture.app, 'Error: GetTunnelStatus unavailable');
    fixture.replies.statuses = async () => offline;
    fixture.replies.tunnel = async () => failed;
    await fixture.app.pollStatuses();
    assertFailed(fixture.app);
  });
}

test('explicit stop clears diagnostic and rejects late polls while stop is pending and afterward', async () => {
  const fixture = await createApp();
  await start(fixture);
  const { app, replies } = fixture;
  replies.tunnel = async () => failed;
  await app.pollStatuses();
  assertFailed(app);
  const poll = deferPoll(fixture);
  const stopped = deferred();
  replies.stop = () => stopped.promise;
  const stop = app.toggleServices();
  assert.equal(app.isStopping.value, true);
  assert.equal(app.configLocked.value, true);
  assert.equal(app.tunnelStatus.value.state, 'stopped');
  assert.equal(app.tunnelStatus.value.error, '');
  assert.equal(app.publicUrl.value, '');
  poll.statuses.resolve(online);
  poll.tunnel.resolve(ready);
  await poll.pending;
  assert.equal(app.tunnelStatus.value.state, 'stopped');
  stopped.resolve();
  await stop;
  replies.statuses = async () => online;
  replies.tunnel = async () => failed;
  await app.pollStatuses();
  assert.equal(app.tunnelStatus.value.state, 'stopped');
  assert.equal(app.tunnelStatus.value.error, '');
  assert.equal(app.configLocked.value, false);
});

for (const outcome of ['resolve', 'reject']) {
  test(`late old start ${outcome} cannot change a restarted generation`, async () => {
    const fixture = await createApp();
    const { app, replies } = fixture;
    const oldStart = deferred();
    replies.start = () => oldStart.promise;
    const firstStart = app.toggleServices();
    await flush();
    assert.equal(app.isStarting.value, true);
    await app.toggleServices();
    assert.equal(app.tunnelStatus.value.state, 'stopped');
    replies.start = async () => {};
    replies.tunnel = async () => ({ ...ready, url: newUrl });
    await start(fixture);
    oldStart[outcome](new Error('old startup failed'));
    await firstStart;
    assert.equal(app.isRunning.value, true);
    assert.equal(app.isStarting.value, false);
    assert.equal(app.publicUrl.value, newUrl);
    assert.equal(app.errorMessage.value, '');
  });
}

test('cancel during config save never starts the canceled attempt', async () => {
  const fixture = await createApp();
  const saved = deferred();
  fixture.replies.save = () => saved.promise;
  const oldStart = fixture.app.toggleServices();
  await fixture.app.toggleServices();
  saved.resolve();
  await oldStart;
  assert.equal(fixture.calls.start, 0);
  assert.equal(fixture.app.tunnelStatus.value.state, 'stopped');
  assert.equal(fixture.app.configLocked.value, false);
});

for (const oldStatus of [ready, failed]) {
  test(`old-run ${oldStatus.state} poll cannot affect new-generation URL or running state`, async () => {
    const fixture = await createApp();
    await start(fixture);
    const poll = deferPoll(fixture);
    await fixture.app.toggleServices();
    fixture.replies.statuses = async () => online;
    fixture.replies.tunnel = async () => ({ ...ready, url: newUrl });
    await start(fixture);
    poll.statuses.resolve(offline);
    poll.tunnel.resolve(oldStatus);
    await poll.pending;
    assert.equal(fixture.app.isRunning.value, true);
    assert.equal(fixture.app.configLocked.value, true);
    assert.equal(fixture.app.publicUrl.value, newUrl);
  });
}

test('newer component poll supersedes older replies without hiding terminal failure', async () => {
  const fixture = await createApp();
  await start(fixture);
  const oldPoll = deferPoll(fixture);
  const newPoll = deferPoll(fixture);
  newPoll.statuses.resolve(offline);
  newPoll.tunnel.resolve(failed);
  await newPoll.pending;
  oldPoll.statuses.resolve(online);
  oldPoll.tunnel.resolve(ready);
  await oldPoll.pending;
  assertFailed(fixture.app);
  assert.equal(fixture.app.isRunning.value, false);
});

test('startup failure preserves terminal diagnostic, clears URL, unlocks config, and accepts final status', async () => {
  const fixture = await createApp();
  const startup = deferred();
  fixture.replies.start = () => startup.promise;
  const starting = fixture.app.toggleServices();
  await flush();
  fixture.replies.tunnel = async () => failed;
  await fixture.app.pollStatuses();
  startup.reject(new Error('start failed'));
  await starting;
  assertFailed(fixture.app);
  assert.equal(fixture.app.errorMessage.value, 'Error: start failed');
  assert.equal(fixture.app.configLocked.value, false);
  fixture.replies.statuses = async () => offline;
  fixture.replies.tunnel = async () => ({ ...failed, error: 'final terminal diagnostic' });
  await fixture.app.pollStatuses();
  assertFailed(fixture.app, 'final terminal diagnostic');
});

test('startup RPC failure exposes its error without retaining a previously ready URL', async () => {
  const fixture = await createApp();
  const startup = deferred();
  fixture.replies.start = () => startup.promise;
  const starting = fixture.app.toggleServices();
  await flush();
  await fixture.app.pollStatuses();
  assert.equal(fixture.app.publicUrl.value, url);
  startup.reject(new Error('start failed'));
  await starting;
  assertFailed(fixture.app, 'Error: start failed');
  assert.equal(fixture.app.configLocked.value, false);
});

test('disabled tunnel stays off on process and startup failures and unlocks config', async () => {
  const fixture = await createApp({ cloudflareTunnelEnabled: false });
  fixture.replies.tunnel = async () => ({ enabled: false, state: 'off', url });
  await start(fixture);
  fixture.replies.statuses = async () => offline;
  await fixture.app.pollStatuses();
  assert.equal(fixture.app.tunnelStatus.value.state, 'off');
  assert.equal(fixture.app.tunnelStatus.value.error, '');
  assert.equal(fixture.app.publicUrl.value, '');
  assert.equal(fixture.app.configLocked.value, false);
  fixture.replies.start = async () => { throw new Error('start failed'); };
  await start(fixture);
  assert.equal(fixture.app.tunnelStatus.value.state, 'off');
  assert.equal(fixture.app.configLocked.value, false);
});

test('empty and default passwords warn but do not block real component startup', async () => {
  for (const adminPassword of ['', '   ', 'admin123']) {
    const fixture = await createApp({ adminPassword });
    assert.match(fixture.app.passwordWarning.value, /此提示不影响启动/);
    await start(fixture);
    assert.equal(fixture.calls.save.length, 1);
    assert.equal(fixture.calls.save[0].adminPassword, adminPassword);
    assert.equal(fixture.calls.start, 1);
    assert.equal(fixture.app.isRunning.value, true);
    assert.equal(fixture.app.publicUrl.value, url);
  }
});

test('terminal tunnel failure cannot revive an old URL before the process reply catches up', async () => {
  const fixture = await createApp();
  await start(fixture);
  fixture.replies.tunnel = async () => failed;
  await fixture.app.pollStatuses();
  assertFailed(fixture.app);
  fixture.replies.tunnel = async () => ready;
  await fixture.app.pollStatuses();
  assertFailed(fixture.app);
  fixture.replies.statuses = async () => offline;
  await fixture.app.pollStatuses();
  assertFailed(fixture.app);
  assert.equal(fixture.app.configLocked.value, false);
  fixture.replies.statuses = async () => online;
  fixture.replies.tunnel = async () => ({ ...ready, url: newUrl });
  await start(fixture);
  assert.equal(fixture.app.publicUrl.value, newUrl);
  assert.equal(fixture.app.tunnelStatus.value.error, '');
});

test('late stop failure leaves the explicitly cleared tunnel stopped', async () => {
  const fixture = await createApp();
  await start(fixture);
  const stopped = deferred();
  fixture.replies.stop = () => stopped.promise;
  const stop = fixture.app.toggleServices();
  stopped.reject(new Error('stop RPC failed'));
  await stop;
  assert.equal(fixture.app.tunnelStatus.value.state, 'stopped');
  assert.equal(fixture.app.tunnelStatus.value.error, '');
  assert.equal(fixture.app.publicUrl.value, '');
  assert.equal(fixture.app.configLocked.value, false);
  assert.equal(fixture.app.errorMessage.value, 'Error: stop RPC failed');
});

test('unmount invalidates pending component polls', async () => {
  const fixture = await createApp();
  await start(fixture);
  const poll = deferPoll(fixture);
  fixture.unmount();
  poll.statuses.resolve(offline);
  poll.tunnel.resolve(failed);
  await poll.pending;
  assert.equal(fixture.app.isRunning.value, true);
  assert.equal(fixture.app.publicUrl.value, url);
});
