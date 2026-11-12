import test from 'node:test';
import assert from 'node:assert/strict';
import { createServiceAttempt } from '../src/serviceAttempt.js';
import { connectionFieldsFor } from '../src/sourceSettings.js';

test('cancel during save invalidates old start and completion', async () => {
  const attempts = createServiceAttempt();
  const first = attempts.begin();
  let release;
  const saved = new Promise(resolve => { release = resolve; });
  let started = false;
  const run = async () => { await saved; if (attempts.current(first)) started = true; };
  const pending = run();
  attempts.cancel();
  release();
  await pending;
  assert.equal(started, false);
  const second = attempts.begin();
  assert.equal(attempts.current(first), false);
  assert.equal(attempts.current(second), true);
});

test('bundled mode hides external connection secrets but keeps credential', () => {
  const source = { connectionFields: [{ key: 'mode' }, { key: 'url', gatewayMode: 'external' }, { key: 'token', gatewayMode: 'external' }, { key: 'credential' }] };
  assert.deepEqual(connectionFieldsFor(source, { qqmusicGatewayMode: 'bundled' }).map(field => field.key), ['mode', 'credential']);
  assert.equal(connectionFieldsFor(source, { qqmusicGatewayMode: 'external' }).length, 4);
});
