package org.thornex.musicparty.controller;
import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.SavedAlbumPage;
import org.thornex.musicparty.service.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SavedAlbumsControllerTest {
    @Test void uninitializedAndProtectedRoomsRejectBeforeReadingPrivateCollection() {
        AppProperties p=new AppProperties(); AuthController auth=mock(AuthController.class);
        SavedAlbumsService albums=mock(SavedAlbumsService.class);NeteaseMusicApiService api=mock(NeteaseMusicApiService.class);
        var c=new SavedAlbumsController(auth,p,albums,api);
        assertEquals(403,c.saved(null,0,25).block().getStatusCode().value());
        when(auth.getRawPassword()).thenReturn("room");
        assertEquals(403,c.saved("wrong",0,25).block().getStatusCode().value());
        assertEquals(403,c.detail("900",null).block().getStatusCode().value());
        verifyNoInteractions(albums,api);
    }
    @Test void roomAdminAndExplicitlyPublicRoomCanReadWithoutCaching() {
        AppProperties p=new AppProperties();p.setAdminPassword("admin");AuthController auth=mock(AuthController.class);when(auth.getRawPassword()).thenReturn("room");
        SavedAlbumsService albums=mock(SavedAlbumsService.class);NeteaseMusicApiService api=mock(NeteaseMusicApiService.class);
        when(albums.getSaved(0,25)).thenReturn(Mono.just(new SavedAlbumPage(List.of(),0,false,0)));
        var c=new SavedAlbumsController(auth,p,albums,api);
        for(String password:List.of("room","admin")){var r=c.saved(password,0,25).block();assertEquals(200,r.getStatusCode().value());assertEquals("no-store",r.getHeaders().getCacheControl());}
        when(auth.getRawPassword()).thenReturn("");assertEquals(200,c.saved(null,0,25).block().getStatusCode().value());
        assertEquals(400,c.detail("invalid",null).block().getStatusCode().value());verifyNoInteractions(api);
    }
    @Test void loginFailureKeepsActionableMessageAndNoStoreHeader() {
        AppProperties p=new AppProperties();AuthController auth=mock(AuthController.class);when(auth.getRawPassword()).thenReturn("");
        SavedAlbumsService albums=mock(SavedAlbumsService.class);
        when(albums.getSaved(0,25)).thenReturn(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED,"更新账号 Cookie")));
        var r=new SavedAlbumsController(auth,p,albums,mock(NeteaseMusicApiService.class)).saved(null,0,25).block();
        assertEquals(401,r.getStatusCode().value());assertEquals("no-store",r.getHeaders().getCacheControl());assertTrue(r.getBody().toString().contains("更新账号"));
    }
}
