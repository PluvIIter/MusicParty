export function sourceSettingsRows(descriptors, sources) {
  const byId = new Map(descriptors.map(descriptor => [descriptor.id, descriptor]));
  const ids = descriptors.map(descriptor => descriptor.id).filter(id => Object.hasOwn(sources, id));
  ids.push(...Object.keys(sources).filter(id => !byId.has(id)).sort());
  return ids.map(id => {
    const descriptor = byId.get(id);
    return {
      id,
      label: descriptor?.label || id,
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
