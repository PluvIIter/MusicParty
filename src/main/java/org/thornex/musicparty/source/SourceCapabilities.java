package org.thornex.musicparty.source;

public record SourceCapabilities(boolean search, boolean lyrics, boolean album,
        boolean userSearch, boolean personalCollections, boolean recommendations,
        boolean download, boolean radioInput) {
}
