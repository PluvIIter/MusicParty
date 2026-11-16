package org.thornex.musicparty.resource;

public record ContainerResource(String id, String name, String platform,
        String collectionType, String coverUrl, int trackCount) {
}
