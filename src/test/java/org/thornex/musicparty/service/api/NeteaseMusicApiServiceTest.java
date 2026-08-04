package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;

import static org.junit.jupiter.api.Assertions.*;

class NeteaseMusicApiServiceTest {

    private AppProperties props;
    private NeteaseMusicApiService service;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.getNetease().setQuality("exhigh");
        service = new NeteaseMusicApiService(WebClient.builder().build(), props);
    }

    @Test
    void isCookieConfiguredReflectsCookieState() {
        assertFalse(service.isCookieConfigured(), "未配置应为 false");
        props.getNetease().setCookie("MUSIC_U=abc; __csrf=def");
        assertTrue(service.isCookieConfigured(), "已配置应为 true");
        props.getNetease().setCookie("YOUR_NETEASE_COOKIE_STRING_HERE");
        assertFalse(service.isCookieConfigured(), "占位符不算配置");
    }

    @Test
    void resolveBrMapsLevelToBitrate() {
        assertEquals(320_000, service.resolveBr("exhigh"));
        assertEquals(320_000, service.resolveBr("EXHIGH"));
        assertEquals(128_000, service.resolveBr("standard"));
        assertEquals(192_000, service.resolveBr("higher"));
        assertEquals(999_000, service.resolveBr("lossless"));
        assertEquals(999_000, service.resolveBr("hires"));
        assertEquals(320_000, service.resolveBr("unknown"));
        assertEquals(320_000, service.resolveBr(null));
    }
}
