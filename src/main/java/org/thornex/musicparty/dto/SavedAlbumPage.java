package org.thornex.musicparty.dto;
import java.util.List;

public record SavedAlbumPage(List<SavedAlbum> items, int total, boolean hasMore, int nextOffset) {
    public record SavedAlbum(String id, String name, List<String> artists, String coverUrl, int trackCount) {}
}
