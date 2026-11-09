package org.thornex.musicparty.dto;

public record EnqueueAlbumRequest(String platform, String albumId, String requestId) {}
