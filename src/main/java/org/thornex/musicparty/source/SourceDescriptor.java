package org.thornex.musicparty.source;

import java.util.List;
import java.util.Set;

public record SourceDescriptor(String id, String label, String iconKey,
        SourceCapabilities capabilities, Set<DeliveryMode> supportedModes,
        List<CollectionCategory> collectionCategories) {
    public SourceDescriptor {
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("Invalid source id");
        }
        if (label == null || label.isBlank() || capabilities == null
                || supportedModes == null || supportedModes.isEmpty()) {
            throw new IllegalArgumentException("Incomplete source descriptor");
        }
        supportedModes = Set.copyOf(supportedModes);
        collectionCategories = List.copyOf(collectionCategories);
        long unique = collectionCategories.stream().map(CollectionCategory::id).distinct().count();
        if (unique != collectionCategories.size()
                || capabilities.personalCollections() != !collectionCategories.isEmpty()) {
            throw new IllegalArgumentException("Inconsistent collection capabilities");
        }
    }
}
