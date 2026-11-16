package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.exception.ApiRequestException;

import static org.junit.jupiter.api.Assertions.*;

class QqMusicCredentialServiceTest {
    private static final String COOKIE = "musicid=123; musickey=secret; openid=open; refresh_token=refresh";

    @Test void cookieIsStoredTrimmedAndPublishedAsConfiguredAccount() {
        var properties = new AppProperties();
        var service = new QqMusicCredentialService(properties);
        assertFalse(service.configured());
        service.updateCookie("  " + COOKIE + "  ");
        assertEquals(COOKIE, service.cookie());
        assertTrue(service.configured());
        assertEquals(COOKIE, properties.getQqmusic().getCookie());
        assertEquals(1, service.revision());
    }

    @Test void rewritingTheSameCookieDoesNotAdvanceTheRevision() {
        var service = new QqMusicCredentialService(new AppProperties());
        service.updateCookie(COOKIE);
        service.updateCookie(" " + COOKIE + " ");
        assertEquals(1, service.revision());
        service.updateCookie("musicid=123; musickey=other");
        assertEquals(2, service.revision());
        assertTrue(service.cookie().contains("musickey=other"));
    }

    @Test void clearingTheCookieReturnsToAnonymous() {
        var service = new QqMusicCredentialService(new AppProperties());
        service.updateCookie(COOKIE);
        service.updateCookie("");
        assertFalse(service.configured());
        assertEquals("", service.cookie());
        assertEquals(2, service.revision());
    }

    @Test void headerBreakingOrOversizedInputIsRejectedWithoutEchoingIt() {
        var service = new QqMusicCredentialService(new AppProperties());
        for (String value : new String[]{"musicid=1; music\nkey=private", "musicid=1\r; musickey=private", "x".repeat(8193)}) {
            var error = assertThrows(ApiRequestException.class, () -> service.updateCookie(value));
            assertFalse(error.getMessage().contains("private"));
        }
        assertFalse(service.configured());
    }

    @Test void legacyJsonCredentialIsRejectedWithAMigrationHint() {
        var service = new QqMusicCredentialService(new AppProperties());
        var error = assertThrows(ApiRequestException.class,
                () -> service.updateCookie("{\"musicid\":123,\"musickey\":\"private\"}"));
        assertTrue(error.getMessage().contains("Cookie"));
        assertFalse(error.getMessage().contains("private"));
    }

    @Test void legacyJsonDeploymentValueDegradesToAnonymous() {
        var properties = new AppProperties();
        properties.getQqmusic().setCookie("{\"musicid\":123,\"musickey\":\"private\"}");
        assertFalse(new QqMusicCredentialService(properties).configured());
    }

    @Test void invalidDeploymentCookieDegradesToAnonymousInsteadOfBreakingStartup() {
        var properties = new AppProperties();
        properties.getQqmusic().setCookie("musicid=1; musickey=bad\nvalue");
        var service = new QqMusicCredentialService(properties);
        assertFalse(service.configured());
        assertEquals("", service.cookie());
    }
    private static final String BROWSER_COOKIE =
            "pgv_pvid=1; qlogin_uid=x; qm_keyst=KEY; uin=1534994648; qqmusic_key=KEY; "
                    + "psrf_qqopenid=OPEN; psrf_qqrefresh_token=RT; psrf_qqaccess_token=AT; "
                    + "psrf_access_token_expiresAt=1799993907; ts_last=y.qq.com/n/ryqq_v2/songDetail/1";

    @Test void browserCookieIsMappedToMusicIdAndMusicKey() {
        var service = new QqMusicCredentialService(new AppProperties());
        service.updateCookie(BROWSER_COOKIE);
        var cookie = service.cookie();
        assertTrue(cookie.contains("musicid=1534994648"));
        assertTrue(cookie.contains("musickey=KEY"));
        assertTrue(cookie.contains("openid=OPEN"));
        assertTrue(cookie.contains("refresh_token=RT"));
        assertTrue(cookie.contains("access_token=AT"));
        assertTrue(cookie.contains("expired_at=1799993907"));
    }

    @Test void mappingIsIdempotent() {
        var service = new QqMusicCredentialService(new AppProperties());
        service.updateCookie(BROWSER_COOKIE);
        String mapped = service.cookie();
        service.updateCookie(mapped);
        assertEquals(mapped, service.cookie());
        assertEquals(1, service.revision());
    }

    @Test void browserCookieWithoutAKeyStaysAnonymous() {
        var service = new QqMusicCredentialService(new AppProperties());
        service.updateCookie("uin=1534994648; pgv_pvid=1");
        assertTrue(service.cookie().contains("uin=1534994648"));
        assertFalse(service.cookie().contains("musicid"));
    }

    @Test void deploymentCookieIsNormalizedToo() {
        var properties = new AppProperties();
        properties.getQqmusic().setCookie(BROWSER_COOKIE);
        assertTrue(new QqMusicCredentialService(properties).cookie().contains("musickey=KEY"));
    }
}