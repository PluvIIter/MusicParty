package org.thornex.musicparty.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Properties;

/**
 * 构建信息（O-02）：版本 / git revision / 构建时间 / Java 版本。
 *
 * <p>数据由构建脚本在 {@code mvnw package} 前写入 {@code target/classes/build-info.properties}
 * （源文件 {@code src/main/resources/build-info.properties} 由 Maven 资源过滤填充，禁止 shell 注入源码）。
 * 读取顺序：build-info.properties → manifest Implementation-Version → 内置兜底。</p>
 */
@Service
@Slf4j
public class BuildInfoService {

    public static final String FALLBACK_VERSION = "1.4.0-fix";
    private static final String RESOURCE = "/build-info.properties";

    private String version = FALLBACK_VERSION;
    private String gitRevision = "unknown";
    private String buildTime = "unknown";
    private String javaVersion = System.getProperty("java.version", "unknown");

    @PostConstruct
    public void init() {
        try (InputStream in = BuildInfoService.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String v = clean(props.getProperty("version"));
                if (v != null) version = v;
                String git = clean(props.getProperty("git.revision"));
                if (git != null) gitRevision = git;
                String time = clean(props.getProperty("build.time"));
                if (time != null) buildTime = time;
            }
        } catch (Exception e) {
            log.warn("Could not read build-info.properties: {}", e.getMessage());
        }

        // Manifest 回退（若资源过滤未生效）
        if (FALLBACK_VERSION.equals(version)) {
            String implVersion = getClass().getPackage().getImplementationVersion();
            if (implVersion != null && !implVersion.isBlank()) {
                version = implVersion;
            }
        }
        log.info("Build info: version={}, gitRevision={}, buildTime={}, javaVersion={}",
                version, gitRevision, buildTime, javaVersion);
    }

    /** 过滤未替换的 Maven 占位符（如 ${git.revision}）与空白。 */
    private static String clean(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty() || v.startsWith("${") || v.equalsIgnoreCase("unknown")) return null;
        return v;
    }

    public String getVersion() {
        return version;
    }

    public String getGitRevision() {
        return gitRevision;
    }

    public String getBuildTime() {
        return buildTime;
    }

    public String getJavaVersion() {
        return javaVersion;
    }
}
