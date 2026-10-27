package org.thornex.musicparty.dto;

public record AlbumImportResult(String requestId, String albumId, String albumName,
                                int total, int added, int duplicates, int limited,
                                String message, boolean error) {
    public static AlbumImportResult failure(EnqueueAlbumRequest request, String message) {
        return new AlbumImportResult(request == null ? "" : request.requestId(),
                request == null ? "" : request.albumId(), "", 0, 0, 0, 0, message, true);
    }
}
