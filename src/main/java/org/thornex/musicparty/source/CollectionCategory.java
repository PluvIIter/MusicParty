package org.thornex.musicparty.source;

public record CollectionCategory(String id, String label, String collectionType, String entryKind) {
    public CollectionCategory {
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("Invalid collection category");
        }
        if (!"TRACK".equals(entryKind) && !"CONTAINER".equals(entryKind)) {
            throw new IllegalArgumentException("Invalid collection entry kind");
        }
    }
}
