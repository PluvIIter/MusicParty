package org.thornex.musicparty.source;

import java.util.List;

public record SourcePage<T>(List<T> items, Integer nextOffset, Long total) {
    public SourcePage {
        items = List.copyOf(items);
    }
}
