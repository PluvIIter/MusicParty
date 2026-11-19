package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

class TrackAlbumNameTest {
    @Test void neteaseReadsBothAlbumShapesWithoutExtraRequests() {
        for (String field : new String[]{"al", "album"}) {
            var properties = new AppProperties();
            properties.getNetease().setBaseUrl("http://netease.test");
            WebClient client = WebClient.builder().exchangeFunction(request -> {
                assertEquals("/song/detail", request.url().getPath());
                return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                        .body("{\"songs\":[{\"id\":1,\"name\":\"Track\",\"" + field
                                + "\":{\"name\":\" Album \"}}]}").build());
            }).build();
            assertEquals("Album", new NeteaseMusicApiService(client, properties).getMusic("1").block().albumName());
        }
    }

    @Test void neteaseAllowsMissingAlbum() {
        var properties = new AppProperties();
        properties.getNetease().setBaseUrl("http://netease.test");
        WebClient client = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                        .body("{\"songs\":[{\"id\":1,\"name\":\"Track\"}]}").build())).build();
        assertNull(new NeteaseMusicApiService(client, properties).getMusic("1").block().albumName());
    }

    @Test void qqReadsAndDecodesAlbumName() {
        var properties = QqMusicTestSupport.properties();
        var credentials = new QqMusicCredentialService(properties);
        WebClient client = QqMusicTestSupport.client((request, ignored) -> {
            assertEquals("/song/songMID/detail", request.url().getPath());
            return Mono.just(QqMusicTestSupport.data(QqMusicTestSupport.json("{\"track\":"
                    + QqMusicTestSupport.TRACK.replace("\"name\":\"Album\"", "\"name\":\"&lt;Album&gt; &amp; More\"") + "}")));
        });
        assertEquals("<Album> & More", QqMusicTestSupport.api(properties, client, credentials)
                .getMusic("songMID").block().albumName());
    }

    @Test void qqAllowsMissingAlbum() {
        var properties = QqMusicTestSupport.properties();
        var credentials = new QqMusicCredentialService(properties);
        WebClient client = QqMusicTestSupport.client((request, ignored) -> Mono.just(QqMusicTestSupport.data(
                QqMusicTestSupport.json("{\"track\":{\"mid\":\"songMID\",\"name\":\"Track\"}}"))));
        assertEquals("", QqMusicTestSupport.api(properties, client, credentials).getMusic("songMID").block().albumName());
    }
}
