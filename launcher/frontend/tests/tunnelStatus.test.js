import test from 'node:test';
import assert from 'node:assert/strict';
import { validTunnelUrl, emptyTunnelStatus, normalizeTunnelStatus, tunnelUrl, copyTunnelUrl, createStatusPoll } from '../src/tunnelStatus.js';

const url = 'https://quiet-blue-fish.trycloudflare.com';
const ready = { enabled: true, state: 'ready', url, error: '' };

test('accepts only HTTPS Quick Tunnel root URLs', () => {
  for (const value of [url, `${url}/`, 'https://a.trycloudflare.com']) {
    assert.equal(validTunnelUrl(value), value);
  }
  for (const value of [undefined, null, 1, '', 'http://quiet-blue-fish.trycloudflare.com',
    `${url}.evil.test`, `${url}:443`, `${url}/path`, `${url}?q=1`, `${url}#fragment`,
    'https://user@quiet-blue-fish.trycloudflare.com', 'https://trycloudflare.com',
    'https://nested.quiet-blue-fish.trycloudflare.com', 'https://-bad.trycloudflare.com',
    'https://bad-.trycloudflare.com', 'https://a_b.trycloudflare.com',
    `https://${'a'.repeat(64)}.trycloudflare.com`, `${url}\n`, ` ${url}`, `${url} `,
    'javascript:alert(1)', 'https://quiet-blue-fish.trycloudflare.com\\@evil.test']) {
    assert.equal(validTunnelUrl(value), '', String(value));
  }
});

test('exposes a URL only while enabled and ready', () => {
  assert.deepEqual(normalizeTunnelStatus(ready), ready);
  for (const state of ['off', 'starting', 'failed', 'stopped']) {
    const status = normalizeTunnelStatus({ ...ready, state });
    assert.equal(status.state, state);
    assert.equal(status.url, '');
  }
  assert.equal(tunnelUrl({ ...ready, enabled: false }), '');
  assert.equal(normalizeTunnelStatus({ ...ready, enabled: 'true' }).state, 'off');
  assert.equal(normalizeTunnelStatus({ ...ready, url: 'https://evil.test' }).state, 'failed');
  assert.equal(normalizeTunnelStatus(undefined).url, '');
  assert.equal(normalizeTunnelStatus({ ...ready, state: 'unknown' }).state, 'failed');
  assert.deepEqual(emptyTunnelStatus('stopped'), { enabled: false, state: 'stopped', url: '', error: '' });
});

test('poll reset rejects delayed ready responses after stop and restart', async () => {
  const polls = createStatusPoll();
  let status = emptyTunnelStatus();
  const old = polls.begin();
  let release;
  const response = new Promise(resolve => { release = resolve; });
  const pending = response.then(value => { if (polls.current(old)) status = normalizeTunnelStatus(value); });
  polls.reset();
  status = emptyTunnelStatus('stopped');
  release(ready);
  await pending;
  assert.equal(status.url, '');
  assert.equal(status.state, 'stopped');
  const restarted = polls.begin();
  assert.equal(polls.current(old), false);
  assert.equal(polls.current(restarted), true);
});

test('newer poll supersedes an older in-flight poll including failure', () => {
  const polls = createStatusPoll();
  const older = polls.begin();
  const newer = polls.begin();
  assert.equal(polls.current(older), false);
  assert.equal(polls.current(newer), true);
  polls.reset();
  assert.equal(polls.current(newer), false);
});

test('copy writes only a validated current ready URL and propagates failure', async () => {
  const written = [];
  await copyTunnelUrl(ready, async value => written.push(value));
  assert.deepEqual(written, [url]);
  for (const status of [{ ...ready, state: 'stopped' }, { ...ready, enabled: false }, { ...ready, url: 'https://evil.test' }]) {
    await assert.rejects(copyTunnelUrl(status, value => written.push(value)), /暂无可复制/);
  }
  assert.deepEqual(written, [url]);
  await assert.rejects(copyTunnelUrl(ready), /剪贴板不可用/);
  await assert.rejects(copyTunnelUrl(ready, async () => { throw new Error('denied'); }), /denied/);
});
