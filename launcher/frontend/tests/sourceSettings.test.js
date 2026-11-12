import assert from 'node:assert/strict';
import test from 'node:test';
import { sourceSettingsRows, deliveryModeLabel, connectionFieldsFor } from '../src/sourceSettings.js';

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
    { id: 'new-source', label: 'New adapter', description: '', supportedModes: ['DIRECT_URL', 'SERVER_DOWNLOAD'], connectionFields: fields },
    { id: 'unknown-source', label: 'unknown-source', description: '', supportedModes: ['DIRECT_URL'], connectionFields: [] },
  ]);
  assert.equal(sources['new-source'].enabled, false);
});

test('QQ gateway description and masked connection fields survive generic rendering', () => {
  const source = { enabled: false, deliveryMode: 'SERVER_DOWNLOAD' };
  const description = 'QQ API 外部网关，不会安装 Python';
  const connectionFields = [
    { key: 'qqmusicBaseUrl', kind: 'url' },
    { key: 'qqmusicGatewayToken', kind: 'password' },
    { key: 'qqmusicCookie', kind: 'password' },
  ];
  const [row] = sourceSettingsRows([{
    id: 'qqmusic', label: 'QQ 音乐', description,
    supportedModes: ['DIRECT_URL', 'SERVER_DOWNLOAD'], connectionFields,
  }], { qqmusic: source });
  assert.equal(row.description, description);
  assert.equal(row.connectionFields[1].kind, 'password');
  assert.equal(row.connectionFields[2].kind, 'password');
  assert.equal(source.enabled, false);
});

test('delivery mode labels use contract values', () => {
  assert.equal(deliveryModeLabel('DIRECT_URL'), '直连音频地址');
  assert.equal(deliveryModeLabel('SERVER_DOWNLOAD'), '服务端下载后交付');
  assert.equal(deliveryModeLabel('FUTURE'), 'FUTURE');
});
