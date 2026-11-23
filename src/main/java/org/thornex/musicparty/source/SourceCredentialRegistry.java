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
    private final Map<String, Object> sourceLocks = new java.util.concurrent.ConcurrentHashMap<>();

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

    public void refresh(String source) {
        var manager = source == null ? null : managers.get(source);
        if (manager == null) throw new ApiRequestException("CAPABILITY_UNAVAILABLE: 此音源不支持凭据刷新");
        if (!manager.isCredentialConfigured()) throw new ApiRequestException("SOURCE_AUTH_REQUIRED: 请先配置完整音源凭据");
        refreshLocked(source, manager);
    }

    /** 定时刷新只处理已启用且有凭据的音源，避免替关闭的源轮换账号登录态。 */
    public void refreshEnabled(String source) {
        var manager = source == null ? null : managers.get(source);
        if (manager == null || !sources.isEnabled(source) || !manager.isCredentialConfigured()) return;
        refreshLocked(source, manager);
    }

    private void refreshLocked(String source, SourceCredentialManager manager) {
        synchronized (lockFor(source)) {
            manager.refreshCredential();
        }
    }

    public void update(String source, String value) {
        var manager = source == null ? null : managers.get(source);
        if (manager == null) throw new ApiRequestException("CAPABILITY_UNAVAILABLE: 此音源不支持凭据管理");
        if (value != null && value.length() > 32768) throw new ApiRequestException("INVALID_CREDENTIAL: 凭据过长");
        // 与刷新共用同一把源级锁：管理员保存的 Cookie 不会被刷新结果覆盖。
        synchronized (lockFor(source)) {
            manager.updateCredential(value == null ? "" : value);
        }
    }

    private Object lockFor(String source) {
        return sourceLocks.computeIfAbsent(source, ignored -> new Object());
    }
}
