package org.thornex.musicparty.enums;

import lombok.Getter;

@Getter
public enum PlatformType {
    NETEASE("netease"),
    BILIBILI("bilibili");

    private final String value;

    PlatformType(String value) {
        this.value = value;
    }

    public static PlatformType fromString(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Platform cannot be null");
        }
        for (PlatformType type : PlatformType.values()) {
            if (type.value.equalsIgnoreCase(text)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown platform: " + text);
    }
}