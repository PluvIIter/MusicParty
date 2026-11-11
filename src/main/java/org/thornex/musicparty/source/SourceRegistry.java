package org.thornex.musicparty.source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.service.api.IMusicApiService;

@Service
public class SourceRegistry {
    private final Map<String, IMusicApiService> adapters;
    private final AppProperties properties;
    private final Map<String, Long> authorizationEpochs = new java.util.concurrent.ConcurrentHashMap<>();

    public long authorizationEpoch(String id) {
        descriptor(id);
        return authorizationEpochs.getOrDefault(id, 0L);
    }

    public SourceRegistry(List<IMusicApiService> services, AppProperties properties) {
        this.properties = properties;
        var registered = new LinkedHashMap<String, IMusicApiService>();
        for (IMusicApiService service : services) {
            SourceDescriptor descriptor = service.descriptor();
            if (!descriptor.id().equals(service.getPlatformName())
                    || registered.putIfAbsent(descriptor.id(), service) != null) {
                throw new IllegalArgumentException("Duplicate or inconsistent source: " + descriptor.id());
            }
            if (!descriptor.supportedModes().contains(service.defaultDeliveryMode())) {
                throw new IllegalArgumentException("Unsupported default delivery mode: " + descriptor.id());
            }
        }
        adapters = java.util.Collections.unmodifiableMap(registered);
        for (String id : properties.getSources().keySet()) {
            if (!adapters.containsKey(id)) throw new IllegalArgumentException("Unknown source: " + id);
        }
        for (String id : adapters.keySet()) deliveryMode(id);
    }

    public IMusicApiService getRequired(String id) {
        IMusicApiService service = adapters.get(id);
        if (service == null) throw new ApiRequestException("SOURCE_NOT_FOUND: 音源不存在");
        if (!isEnabled(id)) throw new ApiRequestException("SOURCE_DISABLED: 音源已禁用");
        return service;
    }

    public boolean isEnabled(String id) {
        if (!adapters.containsKey(id)) return false;
        AppProperties.SourceConfig configured = properties.getSources().get(id);
        if (configured != null && configured.getEnabled() != null) return configured.getEnabled();
        if ("netease".equals(id)) return properties.getNetease().isEnabled();
        if ("bilibili".equals(id)) return properties.getBilibili().isEnabled();
        return true;
    }

    public String externalUrl(String id, String resourceId) {
        IMusicApiService adapter = adapters.get(id);
        if (adapter == null) throw new ApiRequestException("SOURCE_NOT_FOUND: 音源不存在");
        return adapter.externalUrl(resourceId);
    }

    public SourceDescriptor descriptor(String id) {
        IMusicApiService adapter = adapters.get(id);
        if (adapter == null) throw new ApiRequestException("SOURCE_NOT_FOUND: 音源不存在");
        return adapter.descriptor();
    }

    public DeliveryMode deliveryMode(String id) {
        IMusicApiService adapter = adapters.get(id);
        if (adapter == null) throw new ApiRequestException("SOURCE_NOT_FOUND: 音源不存在");
        var configured = properties.getSources().get(id);
        DeliveryMode mode = configured != null && configured.getDeliveryMode() != null
                ? configured.getDeliveryMode() : adapter.defaultDeliveryMode();
        if (!adapter.descriptor().supportedModes().contains(mode)) {
            throw new IllegalArgumentException("Unsupported delivery mode for source: " + id);
        }
        return mode;
    }

    public List<SourceDescriptor> descriptors() {
        return adapters.values().stream().map(IMusicApiService::descriptor).toList();
    }

    public synchronized void update(String id, Boolean enabled, DeliveryMode mode) {
        var descriptor = descriptor(id);
        if (mode != null && mode != deliveryMode(id)) {
            throw new ApiRequestException("DELIVERY_RESTART_REQUIRED: 修改交付类型需要更新部署配置并重启");
        }
        if (mode != null && !descriptor.supportedModes().contains(mode)) {
            throw new ApiRequestException("CAPABILITY_UNAVAILABLE: 音源不支持此交付类型");
        }
        boolean previous = isEnabled(id);
        var config = properties.getSources().computeIfAbsent(id, ignored -> new AppProperties.SourceConfig());
        if (enabled != null) config.setEnabled(enabled);
        if (previous != isEnabled(id)) authorizationEpochs.merge(id, 1L, Long::sum);
    }

    public Map<String, Object> settings() {
        var result = new LinkedHashMap<String, Object>();
        for (String id : adapters.keySet()) {
            result.put(id, Map.of("enabled", isEnabled(id), "deliveryMode", deliveryMode(id).name()));
        }
        return result;
    }

    public Map<String, Object> catalog() {
        var sources = new ArrayList<Map<String, Object>>();
        for (var descriptor : descriptors()) {
            if (!isEnabled(descriptor.id())) continue;
            var item = new LinkedHashMap<String, Object>();
            item.put("id", descriptor.id());
            item.put("label", descriptor.label());
            item.put("iconKey", descriptor.iconKey());
            item.put("capabilities", descriptor.capabilities());
            item.put("collectionCategories", descriptor.collectionCategories());
            item.put("delivery", Map.of("mode", deliveryMode(descriptor.id()).name(),
                    "supportedModes", descriptor.supportedModes().stream().map(Enum::name).sorted().toList()));
            sources.add(item);
        }
        return Map.of("schemaVersion", 2, "catalogRevision", Integer.toUnsignedString(settings().hashCode()), "sources", sources);
    }
}
