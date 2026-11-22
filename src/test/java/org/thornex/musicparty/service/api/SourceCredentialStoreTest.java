package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thornex.musicparty.config.AppProperties;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SourceCredentialStoreTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path directory;

    private AppProperties properties() {
        var properties = new AppProperties();
        properties.setCredentialFile(directory.resolve("credentials.json").toString());
        return properties;
    }

    @Test void persistedValuesSurviveANewStoreInstance() {
        var properties = properties();
        new SourceCredentialStore(MAPPER, properties).put("netease", "MUSIC_U=fresh");
        assertEquals("MUSIC_U=fresh", new SourceCredentialStore(MAPPER, properties).get("netease"));
    }

    @Test void blankValueRemovesThePersistedEntry() {
        var properties = properties();
        var store = new SourceCredentialStore(MAPPER, properties);
        store.put("qqmusic", "musicid=1; musickey=key");
        store.put("qqmusic", " ");
        assertTrue(new SourceCredentialStore(MAPPER, properties).get("qqmusic").isEmpty());
    }

    @Test void refreshedCookieIsRestoredByBothAdapters() {
        var properties = properties();
        var store = new SourceCredentialStore(MAPPER, properties);
        store.put("netease", "MUSIC_U=persisted");
        store.put("qqmusic", "musicid=1; musickey=persisted");

        var netease = new NeteaseMusicApiService(
                org.springframework.web.reactive.function.client.WebClient.builder().build(), properties, store);
        assertTrue(netease.isCookieConfigured());
        assertEquals("musicid=1; musickey=persisted", new QqMusicCredentialService(properties, store).cookie());
    }

    @Test void deployedCookieWinsOverThePersistedValue() {
        var properties = properties();
        var store = new SourceCredentialStore(MAPPER, properties);
        store.put("netease", "MUSIC_U=persisted");
        store.put("qqmusic", "musicid=1; musickey=persisted");
        properties.getNetease().setCookie("MUSIC_U=deployed");
        properties.getQqmusic().setCookie("musicid=2; musickey=deployed");

        var netease = new NeteaseMusicApiService(
                org.springframework.web.reactive.function.client.WebClient.builder().build(), properties, store);
        assertTrue(netease.isCookieConfigured());
        assertEquals("MUSIC_U=deployed", properties.getNetease().getCookie());
        assertEquals("musicid=2; musickey=deployed", new QqMusicCredentialService(properties, store).cookie());
    }

    @Test void unusableDeployedQqCookieFallsBackToThePersistedValue() {
        var properties = properties();
        var store = new SourceCredentialStore(MAPPER, properties);
        store.put("qqmusic", "musicid=1; musickey=persisted");
        properties.getQqmusic().setCookie("uin=1");

        assertEquals("musicid=1; musickey=persisted", new QqMusicCredentialService(properties, store).cookie());
    }
}
