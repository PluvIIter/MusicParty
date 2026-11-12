package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.exception.ApiRequestException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QqMusicCredentialServiceTest {
    @Test
    void anonymousSnapshotCanBeAtomicallyUpdatedAndClearedWithoutSecretStrings() {
        var properties = new AppProperties();
        var service = new QqMusicCredentialService(properties, new ObjectMapper());
        var anonymous = service.snapshot();
        assertFalse(anonymous.configured());
        service.updateCookie("{\"musicid\":123,\"musickey\":\"secret\",\"loginType\":2,\"encrypt_uin\":\"opaque\"}");
        var current = service.snapshot();
        assertEquals(1, current.revision());
        assertEquals("123", current.credential().get("musicid"));
        assertEquals(2, current.credential().get("loginType"));
        assertFalse(current.toString().contains("secret"));
        assertThrows(UnsupportedOperationException.class, () -> current.credential().put("musickey", "other"));
        assertThrows(ApiRequestException.class, () -> service.requireCurrent(anonymous));
        service.updateCookie("{\"musickey\":\"secret\",\"musicid\":\"123\",\"encrypt_uin\":\"opaque\",\"loginType\":2}");
        assertSame(current, service.snapshot());
        service.updateCookie("");
        assertFalse(service.snapshot().configured());
        assertEquals(2, service.snapshot().revision());
        assertEquals("", properties.getQqmusic().getCookie());
    }

    @Test
    void invalidStartupCredentialIsIsolatedAndCannotSilentlyBecomeAnonymousMedia() {
        var properties = new AppProperties();
        properties.getQqmusic().setCookie("invalid-secret-cookie");
        var service = assertDoesNotThrow(() -> new QqMusicCredentialService(properties, new ObjectMapper()));
        assertFalse(service.snapshot().configured());
        assertEquals(0, service.snapshot().revision());
        assertThrows(ApiRequestException.class, () -> service.snapshot().requireValid());
        service.updateCookie("{}");
        assertDoesNotThrow(() -> service.snapshot().requireValid());
        assertFalse(service.snapshot().configured());
        assertEquals(1, service.snapshot().revision());
        assertEquals("", properties.getQqmusic().getCookie());
    }

    @Test
    void credentialBoundsMatchGatewayAndPreservePreviousSnapshot() {
        var properties = new AppProperties();
        var service = new QqMusicCredentialService(properties, new ObjectMapper());
        service.updateCookie("{\"musicid\":\"9223372036854775807\",\"musickey\":\"valid\"}");
        var snapshot = service.snapshot();
        for (String value : List.of("{\"musicid\":\"9223372036854775808\",\"musickey\":\"secret\"}",
                "{\"musicid\":1,\"musickey\":\"" + "s".repeat(4097) + "\"}",
                "{\"musicid\":1,\"musickey\":\"secret\",\"encrypt_uin\":\"" + "e".repeat(257) + "\"}")) {
            assertThrows(ApiRequestException.class, () -> service.updateCookie(value));
            assertSame(snapshot, service.snapshot());
        }
    }

    @Test
    void malformedUpdatesNeverReplaceOldCredentialOrExposeInput() {
        var properties = new AppProperties();
        var service = new QqMusicCredentialService(properties, new ObjectMapper());
        service.updateCookie("{\"musicid\":\"123\",\"musickey\":\"original-secret\"}");
        var before = service.snapshot();
        String stored = properties.getQqmusic().getCookie();
        for (String value : List.of("SESSDATA=secret", "[]", "{\"musicid\":0,\"musickey\":\"secret\"}",
                "{\"musicid\":1.5,\"musickey\":\"secret\"}", "{\"musicid\":\"123\",\"musickey\":\" \"}",
                "{\"musicid\":\"123\",\"musickey\":\"secret\",\"login_type\":2}",
                "{\"musicid\":\"123\",\"musickey\":\"secret\",\"loginType\":3}",
                "{\"musicid\":\"123\",\"musickey\":\"secret\",\"loginType\":\"2\"}",
                "{\"musicid\":\"123\",\"musickey\":\"secret\",\"encrypt_uin\":null}",
                "{\"musicid\":\"123\",\"musickey\":\"secret\\n\"}",
                "{\"musicid\":\"123\",\"musicid\":\"456\",\"musickey\":\"secret\"}",
                "{\"musicid\":\"123\",\"musickey\":\"secret\"} {}")) {
            var error = assertThrows(ApiRequestException.class, () -> service.updateCookie(value), value);
            assertFalse(error.getMessage().contains("secret"));
            assertSame(before, service.snapshot());
            assertEquals(stored, properties.getQqmusic().getCookie());
        }
    }
}
