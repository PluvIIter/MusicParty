package org.thornex.musicparty.resource;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.service.api.IMusicApiService;
import org.thornex.musicparty.source.*;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResourceServiceTest {
    private IMusicApiService adapter() {
        var adapter = mock(IMusicApiService.class);
        when(adapter.getPlatformName()).thenReturn("fixture");
        when(adapter.defaultDeliveryMode()).thenReturn(DeliveryMode.DIRECT_URL);
        when(adapter.descriptor()).thenReturn(new SourceDescriptor("fixture", "Fixture", "music",
                new SourceCapabilities(true, true, false, true, true, false, false, true),
                Set.of(DeliveryMode.DIRECT_URL), List.of(new CollectionCategory("public", "公开收藏", "favorites", "CONTAINER"))));
        return adapter;
    }

    @Test
    void keepsPublicTargetAndCategoryBoundToCursor() {
        var adapter = adapter();
        var playlist = new Playlist("42", "公开", "", 1, "fixture");
        when(adapter.publicCollections("9007199254740993", "public", 0, 20))
                .thenReturn(Mono.just(new SourcePage<>(List.of(playlist), 20, null)));
        var service = new ResourceService(new SourceRegistry(List.of(adapter), new AppProperties()), new ResourceCursor());
        var page = service.collections("fixture", "public", "9007199254740993", null, 20).block();
        assertEquals("favorites", ((ContainerResource) page.items().getFirst()).collectionType());
        assertNotNull(page.nextCursor());
        assertThrows(RuntimeException.class, () -> service.collections("fixture", "public", "different", page.nextCursor(), 20));
        assertThrows(RuntimeException.class, () -> service.collections("fixture", "public", "9007199254740993", page.nextCursor(), 30));
        verify(adapter, times(1)).publicCollections(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void emptyFilteredPageStillAdvancesAndImportTraversesNextPage() {
        var adapter = adapter();
        when(adapter.playlistPage("42", 0, 20)).thenReturn(Mono.just(new SourcePage<>(List.of(), 20, null)));
        var track = new Music("same", "Song", List.of("Artist"), 1000, "fixture", "");
        when(adapter.playlistPage("42", 20, 20)).thenReturn(Mono.just(new SourcePage<>(List.of(track), null, null)));
        var service = new ResourceService(new SourceRegistry(List.of(adapter), new AppProperties()), new ResourceCursor());
        assertEquals(List.of(track), service.importTracks("fixture", "favorites", "42", 100).block());
    }

    @Test
    void unsupportedOrPrivateCollectionIsNotSuccessfulEmptyPage() {
        var adapter = adapter();
        when(adapter.publicCollections(anyString(), eq("public"), anyInt(), anyInt()))
                .thenReturn(Mono.error(new org.thornex.musicparty.exception.ApiRequestException("COLLECTION_NOT_PUBLIC: 不公开")));
        var service = new ResourceService(new SourceRegistry(List.of(adapter), new AppProperties()), new ResourceCursor());
        assertThrows(RuntimeException.class, () -> service.collections("fixture", "saved-albums", "1", null, 20));
        assertThrows(RuntimeException.class, () -> service.collections("fixture", "public", "1", null, 20).block());
        assertThrows(RuntimeException.class, () -> service.tracks("fixture", "favorites", "42", null, 0));
    }

    @Test
    void trackCollectionUsesTrackContractWithoutMasqueradingAsContainer() {
        var adapter = adapter();
        when(adapter.descriptor()).thenReturn(new SourceDescriptor("fixture", "Fixture", "music",
                new SourceCapabilities(true, true, false, true, true, false, false, true),
                Set.of(DeliveryMode.DIRECT_URL), List.of(new CollectionCategory("likes", "公开喜欢", "favorites", "TRACK"))));
        var track = new Music("42", "Song", List.of("Artist"), 1000, "fixture", "");
        when(adapter.publicCollectionTracks("public-user", "likes", 0, 20))
                .thenReturn(Mono.just(new SourcePage<>(List.of(track), null, 1L)));
        var service = new ResourceService(new SourceRegistry(List.of(adapter), new AppProperties()), new ResourceCursor());
        var page = service.collections("fixture", "likes", "public-user", null, 20).block();
        assertInstanceOf(Music.class, page.items().getFirst());
        assertEquals(List.of("Artist"), ((Music) page.items().getFirst()).artists());
        verify(adapter, never()).publicCollections(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void cursorRejectsTamperingAndInvalidOrNonProgressingOffsets() {
        var cursors = new ResourceCursor();
        String token = cursors.issue("binding", 20);
        assertEquals(20, cursors.read(token, "binding"));
        assertThrows(RuntimeException.class, () -> cursors.read(token + "x", "binding"));
        assertThrows(RuntimeException.class, () -> cursors.read(token, "other"));
        var adapter = adapter();
        when(adapter.playlistPage("42", 0, 20)).thenReturn(Mono.just(new SourcePage<>(List.of(), 0, null)));
        var service = new ResourceService(new SourceRegistry(List.of(adapter), new AppProperties()), cursors);
        assertThrows(RuntimeException.class, () -> service.tracks("fixture", "favorites", "42", null, 20).block());
    }
}
