package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.media.MediaConsumer;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BilibiliSourceCookieTest {
    private ClientResponse json(String value) {
        return ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(value).build();
    }

    @Test void allQueriesUseAccountAWithoutReplacingTargetBOrFilteringPrivacyFlags() {
        var properties = new AppProperties();
        properties.getBilibili().setBaseUrl("https://bili.test");
        String cookie = "SESSDATA=accountA; DedeUserID=111; buvid3=device; bili_ticket=ticket";
        properties.getBilibili().setCookie(cookie);
        List<String> paths = new ArrayList<>();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            assertEquals(cookie, request.headers().getFirst("Cookie"));
            String path = request.url().getPath();
            paths.add(path);
            String body;
            if (path.endsWith("/nav")) body = "{\"code\":0,\"data\":{\"wbi_img\":{\"img_url\":\"https://i.test/abcdefghijklmnopqrstuvwxyz012345.png\",\"sub_url\":\"https://i.test/012345abcdefghijklmnopqrstuvwxyz.png\"}}}";
            else if (path.endsWith("/search/type")) body = "{\"code\":0,\"data\":{\"result\":[]}}";
            else if (path.endsWith("/list-all")) {
                assertTrue(request.url().getQuery().contains("up_mid=222"));
                body = "{\"code\":0,\"data\":{\"list\":[{\"id\":333,\"title\":\"Accessible private folder\",\"media_count\":1,\"attr\":1}]}}";
            } else if (path.endsWith("/resource/list")) {
                assertTrue(request.url().getQuery().contains("media_id=333"));
                body = "{\"code\":0,\"data\":{\"info\":{\"id\":333,\"title\":\"Folder\",\"media_count\":0,\"attr\":1},\"medias\":[],\"has_more\":false}}";
            } else if (path.endsWith("/view")) body = "{\"code\":0,\"data\":{\"cid\":10,\"title\":\"Track\",\"duration\":1,\"owner\":{\"name\":\"Artist\"}}}";
            else body = "{\"code\":0,\"data\":{\"timelength\":1000,\"durl\":[{\"url\":\"https://cdn.test/video\"}],\"dash\":{\"audio\":[{\"id\":30280,\"baseUrl\":\"https://cdn.test/audio\"}]}}}";
            return Mono.just(json(body));
        }).build();
        var cookies = new BilibiliCookieService(client, properties);
        var wbi = new BilibiliWbiService(client, cookies);
        var api = new BilibiliMusicApiService(client, properties, wbi, cookies);
        api.searchMusic("track").block();
        api.searchUsers("targetB").block();
        assertEquals("333", api.publicCollections("222", "favorites", 0, 20).block().items().getFirst().id());
        api.containerDetail("favorites", "333").block();
        api.containerPage("favorites", "333", 0, 20).block();
        api.getMusic("BV1abc").block();
        api.resolveMedia("BV1abc", MediaConsumer.BROWSER).block();
        api.resolveMedia("BV1abc", MediaConsumer.SERVER).block();
        assertTrue(paths.contains("/x/web-interface/nav"));
        assertTrue(paths.contains("/x/web-interface/view"));
    }

    @Test void generatedDeviceCookiesSurviveAnonymousRequestsAndSessionReplacement() {
        var properties = new AppProperties();
        List<String> requestedCookies = new ArrayList<>();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            requestedCookies.add(request.headers().getFirst("Cookie"));
            return Mono.just(json(request.url().getPath().endsWith("/spi")
                    ? "{\"code\":0,\"data\":{\"b_3\":\"generated3\",\"b_4\":\"generated4\"}}"
                    : "{\"code\":0,\"data\":{\"ticket\":\"generatedTicket\"}}"));
        }).build();
        var cookies = new BilibiliCookieService(client, properties);
        String anonymous = cookies.getCookieHeader().block();
        assertTrue(anonymous.contains("buvid3=generated3"));
        assertTrue(anonymous.contains("buvid4=generated4"));
        assertTrue(anonymous.contains("b_nut="));
        assertFalse(anonymous.contains("SESSDATA"));
        cookies.updateCookie("SESSDATA=replacement; buvid3=browser3; buvid4=browser4; bili_ticket=browserTicket");
        String replacement = cookies.getCookieHeader().block();
        assertTrue(replacement.contains("SESSDATA=replacement"));
        assertTrue(replacement.contains("buvid3=browser3"));
        assertFalse(replacement.contains("generated"));
        cookies.updateCookie("");
        String cleared = cookies.getCookieHeader().block();
        assertFalse(cleared.contains("SESSDATA"));
        assertTrue(cleared.contains("buvid3=generated3"));
    }

    @Test void replacementAndClearDoNotReusePreviousVideoOrWbiCache() {
        var properties = new AppProperties();
        properties.getBilibili().setBaseUrl("https://bili.test");
        properties.getBilibili().setCookie("SESSDATA=A; buvid3=device; bili_ticket=ticket");
        List<String> viewCookies = new ArrayList<>();
        List<String> navCookies = new ArrayList<>();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            String cookie = request.headers().getFirst("Cookie");
            String path = request.url().getPath();
            if (path.endsWith("/view")) {
                viewCookies.add(cookie);
                return Mono.just(json("{\"code\":0,\"data\":{\"cid\":10,\"title\":\"Track\",\"duration\":1,\"owner\":{\"name\":\"Artist\"}}}"));
            }
            if (path.endsWith("/nav")) {
                navCookies.add(cookie);
                return Mono.just(json("{\"code\":0,\"data\":{\"wbi_img\":{\"img_url\":\"https://i.test/abcdefghijklmnopqrstuvwxyz012345.png\",\"sub_url\":\"https://i.test/012345abcdefghijklmnopqrstuvwxyz.png\"}}}"));
            }
            return Mono.just(json(path.endsWith("/spi") ? "{\"code\":0,\"data\":{\"b_3\":\"anonymous3\",\"b_4\":\"anonymous4\"}}"
                    : "{\"code\":0,\"data\":{\"ticket\":\"anonymousTicket\"}}"));
        }).build();
        var cookies = new BilibiliCookieService(client, properties);
        var wbi = new BilibiliWbiService(client, cookies);
        var api = new BilibiliMusicApiService(client, properties, wbi, cookies);
        for (String next : List.of("SESSDATA=A; buvid3=device; bili_ticket=ticket", "SESSDATA=B; buvid3=deviceB; bili_ticket=ticketB", "")) {
            api.updateCookie(next);
            api.getMusic("BV1abc").block();
            api.getMusic("BV1abc").block();
            wbi.signParams(java.util.Map.of("keyword", "fixture")).block();
        }
        assertEquals(3, viewCookies.size());
        assertEquals(3, navCookies.size());
        assertTrue(viewCookies.get(0).contains("SESSDATA=A"));
        assertTrue(viewCookies.get(1).contains("SESSDATA=B"));
        assertFalse(viewCookies.get(2).contains("SESSDATA"));
        assertTrue(viewCookies.get(2).contains("buvid3=anonymous3"));
        assertEquals(viewCookies, navCookies);
    }

    @Test void delayedTicketAndFingerprintFromOldAccountCannotOverwriteReplacement() {
        for (boolean fingerprint : List.of(true, false)) {
            var properties = new AppProperties();
            properties.getBilibili().setCookie(fingerprint ? "SESSDATA=A" : "SESSDATA=A; buvid3=deviceA");
            var delayed = reactor.core.publisher.Sinks.<ClientResponse>one();
            WebClient client = WebClient.builder().exchangeFunction(request -> delayed.asMono()).build();
            var cookies = new BilibiliCookieService(client, properties);
            var result = new java.util.concurrent.atomic.AtomicReference<String>();
            cookies.getCookieHeader().subscribe(result::set);
            cookies.updateCookie("SESSDATA=B; buvid3=deviceB; bili_ticket=ticketB");
            delayed.tryEmitValue(json(fingerprint
                    ? "{\"code\":0,\"data\":{\"b_3\":\"oldDeviceA\",\"b_4\":\"oldDevice4A\"}}"
                    : "{\"code\":0,\"data\":{\"ticket\":\"oldTicketA\",\"nav\":{\"img\":\"https://i.test/oldA.png\",\"sub\":\"https://i.test/oldSubA.png\"}}}"));
            String current = cookies.getCookieHeader().block();
            assertTrue(current.contains("SESSDATA=B"));
            assertTrue(current.contains("buvid3=deviceB"));
            assertTrue(current.contains("bili_ticket=ticketB"));
            assertFalse(current.contains("old"));
            assertEquals(current, result.get());
            assertTrue(cookies.getFreshWbiKeys().isEmpty());
        }
    }

    @Test void upstreamPermissionFailureIsNotRetriedAsAnonymousOrOtherTarget() {
        var properties = new AppProperties();
        properties.getBilibili().setBaseUrl("https://bili.test");
        properties.getBilibili().setCookie("SESSDATA=A; buvid3=device; bili_ticket=ticket");
        List<String> targets = new ArrayList<>();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            targets.add(request.url().getQuery());
            assertTrue(request.headers().getFirst("Cookie").contains("SESSDATA=A"));
            return Mono.just(json("{\"code\":-403,\"message\":\"permission denied\"}"));
        }).build();
        var cookies = new BilibiliCookieService(client, properties);
        var api = new BilibiliMusicApiService(client, properties, new BilibiliWbiService(client, cookies), cookies);
        assertThrows(ApiRequestException.class, () -> api.publicCollections("222", "favorites", 0, 20).block());
        assertEquals(List.of("up_mid=222"), targets);
    }
}
