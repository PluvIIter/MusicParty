package org.thornex.musicparty.resource;

import java.util.List;

public record ResourcePage<T>(List<T> items, String nextCursor, Long total) {
    public ResourcePage {
        items = List.copyOf(items);
    }
}
