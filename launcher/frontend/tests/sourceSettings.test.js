import assert from 'node:assert/strict';
import test from 'node:test';
import { sourceSettingsRows, deliveryModeLabel } from '../src/sourceSettings.js';

test('settings render arbitrary source descriptors and preserve disabled sources', () => {
  const fields = [{ key: 'adapterCredential', label: 'Media credential', kind: 'password' }];
  const sources = {
    'new-source': { enabled: false, deliveryMode: 'SERVER_DOWNLOAD' },
    'unknown-source': { enabled: true, deliveryMode: 'DIRECT_URL' },
  };
  const rows = sourceSettingsRows([{
    id: 'new-source', label: 'New adapter',
    supportedModes: ['DIRECT_URL', 'SERVER_DOWNLOAD'], connectionFields: fields,
  }], sources);
  assert.deepEqual(rows, [
    { id: 'new-source', label: 'New adapter', supportedModes: ['DIRECT_URL', 'SERVER_DOWNLOAD'], connectionFields: fields },
    { id: 'unknown-source', label: 'unknown-source', supportedModes: ['DIRECT_URL'], connectionFields: [] },
  ]);
  assert.equal(sources['new-source'].enabled, false);
});

test('delivery mode labels use contract values', () => {
  assert.equal(deliveryModeLabel('DIRECT_URL'), '直连音频地址');
  assert.equal(deliveryModeLabel('SERVER_DOWNLOAD'), '服务端下载后交付');
  assert.equal(deliveryModeLabel('FUTURE'), 'FUTURE');
});
