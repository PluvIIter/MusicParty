package org.thornex.musicparty.service.api;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.*;
import org.springframework.web.server.ResponseStatusException;
import org.thornex.musicparty.config.AppProperties;
import reactor.core.publisher.Mono;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SavedAlbumsServiceTest {
    AppProperties props() { AppProperties p = new AppProperties(); p.getNetease().setBaseUrl("http://netease.test"); p.getNetease().setCookie("fixture-cookie"); return p; }
    WebClient client(String json) { return WebClient.builder().exchangeFunction(r -> Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(json).build())).build(); }
    @Test void parsesCollectionWithPaginationAndDeDuplicatesWithoutLosingOffset() {
        var page = new SavedAlbumsService(client("{\"code\":200,\"count\":4,\"hasMore\":true,\"data\":[{\"id\":900,\"name\":\"Saved\",\"artists\":[{\"name\":\"Artist\"}],\"picUrl\":\"http://cover.test/image\",\"size\":3},{\"id\":900},{\"id\":\"invalid\"}]}"), props()).getSaved(0,25).block();
        assertEquals(1, page.items().size()); assertEquals(3, page.nextOffset()); assertEquals(4,page.total()); assertTrue(page.hasMore());
        assertEquals("https://cover.test/image",page.items().getFirst().coverUrl()); assertEquals(List.of("Artist"),page.items().getFirst().artists());
    }
    @Test void emptyPageCannotCreateInfiniteMoreLoop() {
        var page = new SavedAlbumsService(client("{\"code\":200,\"count\":10,\"hasMore\":true,\"data\":[]}"),props()).getSaved(0,25).block();
        assertTrue(page.items().isEmpty()); assertFalse(page.hasMore());
    }
    @Test void invalidParametersDisabledSourceAndMissingCookieDoNotContactApi() {
        WebClient noCalls=WebClient.builder().exchangeFunction(r->{fail("Must not contact API");return Mono.empty();}).build();
        AppProperties p=props(); SavedAlbumsService s=new SavedAlbumsService(noCalls,p);
        for(int[] pair:List.of(new int[]{-1,25},new int[]{100001,25},new int[]{0,0},new int[]{0,51}))
            assertEquals(400,assertThrows(ResponseStatusException.class,()->s.getSaved(pair[0],pair[1]).block()).getStatusCode().value());
        p.getNetease().setCookie(""); assertEquals(401,assertThrows(ResponseStatusException.class,()->s.getSaved(0,25).block()).getStatusCode().value());
        p.getNetease().setEnabled(false); assertEquals(409,assertThrows(ResponseStatusException.class,()->s.getSaved(0,25).block()).getStatusCode().value());
    }
    @Test void loginExpiryAndMalformedResponsesAreSanitized() {
        for(String body:List.of("{\"code\":301,\"message\":\"fixture-secret\"}","{\"code\":200,\"data\":{\"private\":\"fixture-secret\"}}")) {
            var error=assertThrows(ResponseStatusException.class,()->new SavedAlbumsService(client(body),props()).getSaved(0,25).block());
            assertFalse(error.getReason().contains("fixture-secret")); assertEquals(body.contains("301")?401:502,error.getStatusCode().value());
        }
    }
    @Test void httpFailureNeverReturnsUpstreamBody() {
        WebClient c=WebClient.builder().exchangeFunction(r->Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).body("fixture-secret").build())).build();
        var e=assertThrows(ResponseStatusException.class,()->new SavedAlbumsService(c,props()).getSaved(0,25).block());
        assertEquals(502,e.getStatusCode().value());assertFalse(e.getReason().contains("fixture-secret"));
    }
}
