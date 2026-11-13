package org.thornex.musicparty.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.thornex.musicparty.config.AppProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Service
@Slf4j
public class SecretStoreService {
    private final AppProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path path;
    private volatile String adminPassword;
    private volatile String neteaseCookie;
    private volatile String bilibiliCookie;

    public SecretStoreService(AppProperties properties) {
        this.properties = properties;
        this.path = Paths.get(StringUtils.hasText(properties.getSecretsFile()) ? properties.getSecretsFile() : "data/secrets.json");
        this.adminPassword = normalize(properties.getAdminPassword());
        this.neteaseCookie = normalize(properties.getNetease().getCookie());
        this.bilibiliCookie = normalize(properties.getBilibili().getCookie());
    }

    @PostConstruct
    public void load() {
        if (!Files.isRegularFile(path)) return;
        try {
            JsonNode root = mapper.readTree(Files.readString(path));
            String value = normalize(root.path("adminPassword").asText(null));
            if (value != null) adminPassword = value;
            value = normalize(root.path("neteaseCookie").asText(null));
            if (value != null) neteaseCookie = value;
            value = normalize(root.path("bilibiliCookie").asText(null));
            if (value != null) bilibiliCookie = value;
            log.info("Secret store loaded (admin={}, netease={}, bilibili={}).", adminPassword != null, neteaseCookie != null, bilibiliCookie != null);
        } catch (Exception e) {
            log.warn("Secret store could not be read; using deployment configuration fallback: {}", e.getMessage());
        }
    }

    public String getAdminPassword() { return adminPassword; }
    public String getNeteaseCookie() { return neteaseCookie; }
    public String getBilibiliCookie() { return bilibiliCookie; }

    public synchronized void updateNeteaseCookie(String value) { neteaseCookie = normalize(value); persist(); }
    public synchronized void updateBilibiliCookie(String value) { bilibiliCookie = normalize(value); persist(); }

    private void persist() {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            ObjectNode root = mapper.createObjectNode();
            if (adminPassword != null) root.put("adminPassword", adminPassword);
            if (neteaseCookie != null) root.put("neteaseCookie", neteaseCookie);
            if (bilibiliCookie != null) root.put("bilibiliCookie", bilibiliCookie);
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            try { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) { log.warn("Secret store could not be persisted: {}", e.getMessage()); }
    }

    private static String normalize(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
}
