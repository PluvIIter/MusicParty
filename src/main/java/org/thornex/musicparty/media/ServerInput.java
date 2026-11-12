package org.thornex.musicparty.media;

import java.util.Map;

public record ServerInput(String location, Map<String, String> headers, String key) {
    public ServerInput {
        headers = Map.copyOf(headers);
    }
}
