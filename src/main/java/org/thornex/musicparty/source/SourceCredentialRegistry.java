package org.thornex.musicparty.source;

import org.springframework.stereotype.Service;
import org.thornex.musicparty.exception.ApiRequestException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SourceCredentialRegistry {
    private final SourceRegistry sources;
    private final Map<String, SourceCredentialManager> managers;

    public SourceCredentialRegistry(SourceRegistry sources, List<SourceCredentialManager> managers) {
        this.sources = sources;
        var registered = new LinkedHashMap<String, SourceCredentialManager>();
        for (var manager : managers) {
            sources.descriptor(manager.credentialSourceId());
            if (registered.putIfAbsent(manager.credentialSourceId(), manager) != null) {
                throw new IllegalArgumentException("Duplicate credential manager");
            }
        }
        this.managers = Map.copyOf(registered);
    }

    public Map<String, Object> catalog() {
        return Map.of("sources", sources.descriptors().stream().map(descriptor -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("id", descriptor.id());
            item.put("label", descriptor.label());
            item.put("enabled", sources.isEnabled(descriptor.id()));
            var manager = managers.get(descriptor.id());
            item.put("credential", manager == null ? null : Map.of("type", manager.credentialType(),
                    "label", manager.credentialLabel(), "configured", manager.isCredentialConfigured()));
            return item;
        }).toList());
    }

    public void update(String source, String value) {
        var manager = source == null ? null : managers.get(source);
        if (manager == null) throw new ApiRequestException("CAPABILITY_UNAVAILABLE: 此音源不支持凭据管理");
        if (value != null && value.length() > 32768) throw new ApiRequestException("INVALID_CREDENTIAL: 凭据过长");
        manager.updateCredential(value == null ? "" : value);
    }
}
