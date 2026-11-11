package org.thornex.musicparty.dto;
public record AdminPlayerActionRequest(String action, String playMode) {
    public AdminPlayerActionRequest(String action) { this(action, null); }
}
