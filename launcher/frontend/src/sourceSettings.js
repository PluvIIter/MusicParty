export function sourceSettingsRows(descriptors, sources) {
  const byId = new Map(descriptors.map(descriptor => [descriptor.id, descriptor]));
  return Object.keys(sources).sort().map(id => {
    const descriptor = byId.get(id);
    return {
      id,
      label: descriptor?.label || id,
      description: descriptor?.description || '',
      supportedModes: descriptor?.supportedModes || [sources[id].deliveryMode],
      connectionFields: descriptor?.connectionFields || [],
    };
  });
}

export function deliveryModeLabel(mode) {
  return {
    DIRECT_URL: '直连音频地址',
    SERVER_DOWNLOAD: '服务端下载后交付',
  }[mode] || mode;
}
