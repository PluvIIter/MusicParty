const tunnelStates = new Set(['off', 'starting', 'ready', 'failed', 'stopped']);
const quickTunnelUrl = /^https:\/\/([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\.trycloudflare\.com\/?$/;

export function validTunnelUrl(value) {
  return typeof value === 'string' && value.match(quickTunnelUrl)?.[0] === value ? value : '';
}

export function emptyTunnelStatus(state = 'off', error = '') {
  return { enabled: false, state, url: '', error };
}

export function normalizeTunnelStatus(status) {
  if (!status || !tunnelStates.has(status.state)) {
    return emptyTunnelStatus('failed', '无法读取公网隧道状态');
  }
  const enabled = status.enabled === true;
  const error = typeof status.error === 'string' ? status.error : '';
  if (!enabled || status.state !== 'ready') {
    return { enabled, state: enabled ? status.state : 'off', url: '', error };
  }
  const url = validTunnelUrl(status.url);
  return url
    ? { enabled, state: 'ready', url, error }
    : { enabled, state: 'failed', url: '', error: '公网隧道地址无效，已禁止访问和复制' };
}

export function tunnelUrl(status) {
  return normalizeTunnelStatus(status).url;
}

export async function copyTunnelUrl(status, writeText) {
  const url = tunnelUrl(status);
  if (!url) throw new Error('暂无可复制的公网隧道地址');
  if (typeof writeText !== 'function') throw new Error('剪贴板不可用');
  await writeText(url);
}

export function createStatusPoll() {
  let generation = 0;
  let sequence = 0;
  return {
    begin: () => ({ generation, sequence: ++sequence }),
    reset: () => { generation++; },
    current: token => token.generation === generation && token.sequence === sequence,
  };
}
