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
}
