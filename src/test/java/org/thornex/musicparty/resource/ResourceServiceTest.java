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
    void credentialRotationInvalidatesCollectionAndTrackCursorsWithoutChangingTarget() {
        var adapter = adapter();
        when(adapter.credentialRevision()).thenReturn(4L);
        when(adapter.publicCollections("target-b", "public", 0, 20))
                .thenReturn(Mono.just(new SourcePage<>(List.of(), 20, null)));
        when(adapter.containerPage("favorites", "42", 0, 20))
                .thenReturn(Mono.just(new SourcePage<>(List.of(), 20, null)));
        var service = new ResourceService(new SourceRegistry(List.of(adapter), new AppProperties()), new ResourceCursor());
        var collections = service.collections("fixture", "public", "target-b", null, 20).block();
        var tracks = service.tracks("fixture", "favorites", "42", null, 20).block();
        when(adapter.credentialRevision()).thenReturn(5L);
        assertThrows(RuntimeException.class, () -> service.collections("fixture", "public", "target-b", collections.nextCursor(), 20));
        assertThrows(RuntimeException.class, () -> service.tracks("fixture", "favorites", "42", tracks.nextCursor(), 20));
        verify(adapter, never()).publicCollections(anyString(), anyString(), eq(20), anyInt());
        verify(adapter, never()).containerPage(anyString(), anyString(), eq(20), anyInt());
        verify(adapter).publicCollections("target-b", "public", 0, 20);
    }

    @Test
    void sourceReenableInvalidatesCursorEvenWhenCredentialRevisionStaysTheSame() {
        var adapter = adapter();
        when(adapter.publicCollections("target-b", "public", 0, 20))
                .thenReturn(Mono.just(new SourcePage<>(List.of(), 20, null)));
        var registry = new SourceRegistry(List.of(adapter), new AppProperties());
        var service = new ResourceService(registry, new ResourceCursor());
        var page = service.collections("fixture", "public", "target-b", null, 20).block();
        registry.update("fixture", false, null);
        registry.update("fixture", true, null);
        assertThrows(RuntimeException.class, () -> service.collections("fixture", "public", "target-b", page.nextCursor(), 20));
    }

    @Test
    void delayedCollectionResponseCannotReturnUnderNewCredentials() {
        var adapter = adapter();
        var response = reactor.core.publisher.Sinks.<SourcePage<Playlist>>one();
        when(adapter.publicCollections("target-b", "public", 0, 20)).thenReturn(response.asMono());
        var service = new ResourceService(new SourceRegistry(List.of(adapter), new AppProperties()), new ResourceCursor());
        var pending = service.collections("fixture", "public", "target-b", null, 20).toFuture();
        when(adapter.credentialRevision()).thenReturn(1L);
        response.tryEmitValue(new SourcePage<>(List.of(new Playlist("42", "Old account view", "", 1, "fixture")), null, 1L));
        assertTrue(pending.isCompletedExceptionally());
    }

    @Test
    void delayedSearchAndContainerResponsesRejectCredentialRotation() {
        var adapter = adapter();
        var search = reactor.core.publisher.Sinks.<List<Music>>one();
        var detail = reactor.core.publisher.Sinks.<Playlist>one();
        when(adapter.searchMusic("query")).thenReturn(search.asMono());
        when(adapter.containerDetail("favorites", "42")).thenReturn(detail.asMono());
        var service = new ResourceService(new SourceRegistry(List.of(adapter), new AppProperties()), new ResourceCursor());
        var pendingSearch = service.search("fixture", "query").toFuture();
        var pendingDetail = service.container("fixture", "favorites", "42").toFuture();
        when(adapter.credentialRevision()).thenReturn(1L);
        search.tryEmitValue(List.of());
        detail.tryEmitValue(new Playlist("42", "Old account view", "", 0, "fixture"));
        assertTrue(pendingSearch.isCompletedExceptionally());
        assertTrue(pendingDetail.isCompletedExceptionally());
    }

    @Test
    void albumCursorIsBoundToCredentialRevision() {
        var adapter = adapter();
        when(adapter.descriptor()).thenReturn(new SourceDescriptor("fixture", "Fixture", "music",
                new SourceCapabilities(true, true, true, true, true, false, false, true),
                Set.of(DeliveryMode.DIRECT_URL), List.of(new CollectionCategory("public", "公开收藏", "favorites", "CONTAINER"))));
        var song = new Music("first", "Song", List.of(), 1000, "fixture", "", null);
        when(adapter.getAlbum("album")).thenReturn(Mono.just(new org.thornex.musicparty.dto.AlbumInfo(
                "album", "Album", List.of(), "", 2, List.of(song, song))));
        var service = new ResourceService(new SourceRegistry(List.of(adapter), new AppProperties()), new ResourceCursor());
        var page = service.tracks("fixture", "album", "album", null, 1).block();
        when(adapter.credentialRevision()).thenReturn(1L);
        assertThrows(RuntimeException.class, () -> service.tracks("fixture", "album", "album", page.nextCursor(), 1));
        verify(adapter, times(1)).getAlbum("album");
    }

    @Test
    void requestCreatedBeforeCredentialRotationCannotSubscribeAsNewAccount() {
        var adapter = adapter();
        var service = new ResourceService(new SourceRegistry(List.of(adapter), new AppProperties()), new ResourceCursor());
        var pending = service.collections("fixture", "public", "target-b", null, 20);
        when(adapter.credentialRevision()).thenReturn(1L);
        assertThrows(RuntimeException.class, pending::block);
        verify(adapter, never()).publicCollections(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void credentialRotationBetweenImportPagesCannotMixAccountViews() {
        var adapter = adapter();
        var second = reactor.core.publisher.Sinks.<SourcePage<Music>>one();
        var firstTrack = new Music("first", "First", List.of(), 1000, "fixture", "", null);
        when(adapter.containerPage("favorites", "42", 0, 20))
                .thenReturn(Mono.just(new SourcePage<>(List.of(firstTrack), 20, null)));
        when(adapter.containerPage("favorites", "42", 20, 20)).thenReturn(second.asMono());
        var service = new ResourceService(new SourceRegistry(List.of(adapter), new AppProperties()), new ResourceCursor());
        var pending = service.importTracks("fixture", "favorites", "42", 100).toFuture();
        when(adapter.credentialRevision()).thenReturn(1L);
        second.tryEmitValue(new SourcePage<>(List.of(new Music("second", "Second", List.of(), 1000, "fixture", "", null)), null, null));
        assertTrue(pending.isCompletedExceptionally());
    }

    @Test
    void emptyFilteredPageStillAdvancesAndImportTraversesNextPage() {
        var adapter = adapter();
        when(adapter.containerPage("favorites", "42", 0, 20)).thenReturn(Mono.just(new SourcePage<>(List.of(), 20, null)));
        var track = new Music("same", "Song", List.of("Artist"), 1000, "fixture", "", null);
        when(adapter.containerPage("favorites", "42", 20, 20)).thenReturn(Mono.just(new SourcePage<>(List.of(track), null, null)));
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
        var track = new Music("42", "Song", List.of("Artist"), 1000, "fixture", "", null);
        when(adapter.publicCollectionTracks("public-user", "likes", 0, 20))
                .thenReturn(Mono.just(new SourcePage<>(List.of(track), null, 1L)));
        var service = new ResourceService(new SourceRegistry(List.of(adapter), new AppProperties()), new ResourceCursor());
        var page = service.collections("fixture", "likes", "public-user", null, 20).block();
        assertInstanceOf(Music.class, page.items().getFirst());
        assertEquals(List.of("Artist"), ((Music) page.items().getFirst()).artists());
        verify(adapter, never()).publicCollections(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void identicalContainerIdsKeepTheirTypesThroughAdapterCalls() {
        var adapter = adapter();
        when(adapter.descriptor()).thenReturn(new SourceDescriptor("fixture", "Fixture", "music",
                new SourceCapabilities(true, true, false, true, true, false, false, true),
                Set.of(DeliveryMode.DIRECT_URL), List.of(
                        new CollectionCategory("one", "One", "favorites", "CONTAINER"),
                        new CollectionCategory("two", "Two", "playlist", "CONTAINER"))));
        when(adapter.containerDetail("favorites", "same")).thenReturn(Mono.just(new Playlist("same", "Favorite", "", 0, "fixture")));
        when(adapter.containerDetail("playlist", "same")).thenReturn(Mono.just(new Playlist("same", "Playlist", "", 0, "fixture")));
        when(adapter.containerPage(eq("favorites"), eq("same"), anyInt(), anyInt())).thenReturn(Mono.just(new SourcePage<>(List.of(), null, 0L)));
        when(adapter.containerPage(eq("playlist"), eq("same"), anyInt(), anyInt())).thenReturn(Mono.just(new SourcePage<>(List.of(), null, 0L)));
        var service = new ResourceService(new SourceRegistry(List.of(adapter), new AppProperties()), new ResourceCursor());
        assertEquals("Favorite", service.container("fixture", "favorites", "same").block().name());
        assertEquals("Playlist", service.container("fixture", "playlist", "same").block().name());
        service.tracks("fixture", "favorites", "same", null, 20).block();
        service.tracks("fixture", "playlist", "same", null, 20).block();
        verify(adapter).containerPage("favorites", "same", 0, 20);
        verify(adapter).containerPage("playlist", "same", 0, 20);
    }

    @Test
    void cursorRejectsTamperingAndInvalidOrNonProgressingOffsets() {
        var cursors = new ResourceCursor();
        String token = cursors.issue("binding", 20);
        assertEquals(20, cursors.read(token, "binding"));
        assertThrows(RuntimeException.class, () -> cursors.read(token + "x", "binding"));
        assertThrows(RuntimeException.class, () -> cursors.read(token, "other"));
        var adapter = adapter();
        when(adapter.containerPage("favorites", "42", 0, 20)).thenReturn(Mono.just(new SourcePage<>(List.of(), 0, null)));
        var service = new ResourceService(new SourceRegistry(List.of(adapter), new AppProperties()), cursors);
        assertThrows(RuntimeException.class, () -> service.tracks("fixture", "favorites", "42", null, 20).block());
    }
}
