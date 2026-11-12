package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.exception.ApiRequestException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QqMusicCredentialServiceTest {
    @Test void supportsExactlyNativeWebCookieFieldsWithoutInventingUnsupportedFields() {
        var properties = new AppProperties();
        var service = new QqMusicCredentialService(properties, new ObjectMapper());
        service.updateCookie("{\"musicid\":123,\"musickey\":\"secret\",\"openid\":\"open\",\"refresh_token\":\"refresh\",\"access_token\":\"access\",\"expired_at\":1800000000,\"unionid\":\"union\",\"str_musicid\":\"account-string\",\"refresh_key\":\"refreshkey\"}");
        var snapshot = service.snapshot();
        assertEquals(9, snapshot.credential().size());
        assertEquals("account-string", snapshot.credential().get("str_musicid"));
        assertTrue(snapshot.cookieHeader().contains("expired_at=\"1800000000\""));
        assertFalse(snapshot.toString().contains("secret"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.credential().put("musickey", "other"));
        service.updateCookie("{}");
        assertFalse(service.snapshot().configured());
        assertEquals(2, service.snapshot().revision());
        assertThrows(ApiRequestException.class, () -> service.requireCurrent(snapshot));
    }

    @Test void nativeCookieQuotingEscapesSeparatorsWithoutPercentEncodingCredential() {
        var properties = new AppProperties();
        var service = new QqMusicCredentialService(properties, new ObjectMapper());
        service.updateCookie("{\"musicid\":123,\"musickey\":\"a;b,\\\"c\\\\d +/%\"}");
        assertEquals("musicid=\"123\"; musickey=\"a\\073b\\054\\\"c\\\\d +/%\"; str_musicid=\"123\"", service.snapshot().cookieHeader());
    }

    @Test void invalidStartupIsIsolatedButPrivateSnapshotRemainsExplicitlyInvalid() {
        var properties = new AppProperties();
        properties.getQqmusic().setCookie("invalid-secret");
        var service = new QqMusicCredentialService(properties, new ObjectMapper());
        assertFalse(service.snapshot().configured());
        assertThrows(ApiRequestException.class, () -> service.snapshot().requireValid());
        service.updateCookie("");
        assertDoesNotThrow(() -> service.snapshot().requireValid());
    }

    @Test void malformedUpdatesCannotReplaceCurrentAccountOrLeakInput() {
        var properties = new AppProperties();
        var service = new QqMusicCredentialService(properties, new ObjectMapper());
        service.updateCookie("{\"musicid\":123,\"musickey\":\"original\"}");
        var before = service.snapshot();
        for (String value : List.of("[]", "{\"musicid\":0,\"musickey\":\"secret\"}",
                "{\"musicid\":123,\"musickey\":\"secret\",\"loginType\":2}",
                "{\"musicid\":123,\"musickey\":\"secret\",\"encrypt_uin\":\"old\"}",
                "{\"musicid\":123,\"musickey\":\"secret\\n\"}",
                "{\"musicid\":123,\"musicid\":456,\"musickey\":\"secret\"}",
                "{\"musicid\":123,\"musickey\":\"secret\"} {}")) {
            assertFalse(assertThrows(ApiRequestException.class, () -> service.updateCookie(value)).getMessage().contains("secret"));
            assertSame(before, service.snapshot());
        }
    }
}
