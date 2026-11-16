package org.thornex.musicparty.source;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.thornex.musicparty.config.AppProperties;

import static org.junit.jupiter.api.Assertions.*;

class SourceConfigBindingTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class Config {}

    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test void canonicalSourceMapBindsEnabledAndDeliveryIndependentlyOfCredentials() {
        context.withPropertyValues("app.music-api.sources.netease.enabled=false",
                "app.music-api.sources.netease.delivery-mode=SERVER_DOWNLOAD",
                "app.music-api.netease.cookie=fixture").run(application -> {
            assertNull(application.getStartupFailure());
            var properties = application.getBean(AppProperties.class);
            assertFalse(properties.getSources().get("netease").getEnabled());
            assertEquals(DeliveryMode.SERVER_DOWNLOAD, properties.getSources().get("netease").getDeliveryMode());
            assertEquals("fixture", properties.getNetease().getCookie());
        });
    }

    @Test void completeLauncherArgumentsBindToStrictCurrentBackendSchema() {
        String cookie = "MUSIC_U=private token; csrf=中文";
        context.withPropertyValues(
                "server.address=0.0.0.0", "server.port=8080",
                "app.music-api.base-url=http://localhost:8080",
                "app.music-api.admin-password=fixture-admin",
                "app.music-api.author-name=ThorNex", "app.music-api.back-words=THORNEX",
                "app.music-api.netease.base-url=http://127.0.0.1:3000",
                "app.music-api.netease.cookie=" + cookie, "app.music-api.netease.quality=exhigh",
                "app.music-api.bilibili.cookie=SESSDATA=private; buvid3=device",
                "app.music-api.queue.max-size=1000", "app.music-api.queue.history-size=50",
                "app.music-api.queue.max-user-songs=100", "app.music-api.player.max-playlist-import-size=100",
                "app.music-api.chat.max-history-size=1000", "app.music-api.chat.min-interval-ms=1000",
                "app.music-api.chat.max-message-length=200", "app.music-api.cache.max-size=1GB",
                "app.music-api.auth.rate-limit-enabled=true", "app.music-api.auth.max-attempts=5",
                "app.music-api.auth.window-seconds=60", "app.music-api.auth.block-duration-seconds=300",
                "logging.level.root=INFO", "logging.level.org.thornex.musicparty=DEBUG",
                "app.music-api.sources.bilibili.enabled=true",
                "app.music-api.sources.bilibili.delivery-mode=SERVER_DOWNLOAD",
                "app.music-api.sources.netease.enabled=true",
                "app.music-api.sources.netease.delivery-mode=DIRECT_URL").run(application -> {
            assertNull(application.getStartupFailure());
            var properties = application.getBean(AppProperties.class);
            assertEquals(cookie, properties.getNetease().getCookie());
            assertEquals("SESSDATA=private; buvid3=device", properties.getBilibili().getCookie());
            assertEquals("http://127.0.0.1:3000", properties.getNetease().getBaseUrl());
            assertEquals("fixture-admin", properties.getAdminPassword());
            assertTrue(properties.getAuth().isRateLimitEnabled());
            assertEquals(5, properties.getAuth().getMaxAttempts());
            assertEquals(60, properties.getAuth().getWindowSeconds());
            assertEquals(300, properties.getAuth().getBlockDurationSeconds());
            assertEquals(1L << 30, properties.getCache().getMaxSize().toBytes());
            assertEquals(DeliveryMode.DIRECT_URL, properties.getSources().get("netease").getDeliveryMode());
            assertEquals(DeliveryMode.SERVER_DOWNLOAD, properties.getSources().get("bilibili").getDeliveryMode());
        });
    }

    @Test void obsoletePerAdapterEnabledFieldsFailBinding() {
        for (String source : List.of("netease", "bilibili")) {
            context.withPropertyValues("app.music-api." + source + ".enabled=false")
                    .run(application -> assertNotNull(application.getStartupFailure()));
        }
    }
}
