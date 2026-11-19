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

test('settings follow descriptor order instead of config key order', () => {
  const descriptors = ['netease', 'qqmusic', 'bilibili', 'fixture-z', 'fixture-a', 'absent']
    .map(id => ({ id, label: id }));
  const sources = Object.fromEntries(
    ['unknown-z', 'bilibili', 'fixture-a', 'qqmusic', 'unknown-a', 'netease', 'fixture-z']
      .map(id => [id, { enabled: false, deliveryMode: 'DIRECT_URL' }]),
  );
  const rows = sourceSettingsRows(descriptors, sources);
  assert.deepEqual(rows.map(row => row.id),
    ['netease', 'qqmusic', 'bilibili', 'fixture-z', 'fixture-a', 'unknown-a', 'unknown-z']);
  assert.deepEqual(descriptors.map(descriptor => descriptor.id),
    ['netease', 'qqmusic', 'bilibili', 'fixture-z', 'fixture-a', 'absent']);
  assert.equal(Object.values(sources).every(source => source.enabled === false), true);
});

test('settings retain dynamic descriptor order without built-in sources', () => {
  const descriptors = [{ id: 'fixture-z' }, { id: 'fixture-a' }];
  const sources = {
    'fixture-a': { enabled: true, deliveryMode: 'DIRECT_URL' },
    'fixture-z': { enabled: false, deliveryMode: 'SERVER_DOWNLOAD' },
  };
  assert.deepEqual(sourceSettingsRows(descriptors, sources).map(row => row.id), ['fixture-z', 'fixture-a']);
});

test('QQ source exposes the same shape as NetEase', () => {
  const connectionFields = [
    { key: 'qqmusicCookie', kind: 'password' },
    { key: 'qqmusicQuality', kind: 'select' },
  ];
  const [row] = sourceSettingsRows([{
    id: 'qqmusic', label: 'QQ 音乐',
    supportedModes: ['DIRECT_URL', 'SERVER_DOWNLOAD'], connectionFields,
  }], { qqmusic: { enabled: false, deliveryMode: 'SERVER_DOWNLOAD' } });
  assert.deepEqual(row.connectionFields.map(field => field.key), ['qqmusicCookie', 'qqmusicQuality']);
  assert.equal(row.connectionFields[0].kind, 'password');
  assert.equal(row.description, undefined);
});

test('delivery mode labels use contract values', () => {
  assert.equal(deliveryModeLabel('DIRECT_URL'), '直连音频地址');
  assert.equal(deliveryModeLabel('SERVER_DOWNLOAD'), '服务端下载后交付');
  assert.equal(deliveryModeLabel('FUTURE'), 'FUTURE');
});
