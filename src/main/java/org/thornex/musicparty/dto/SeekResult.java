package org.thornex.musicparty.dto;

public record SeekResult(String requestId, String playbackId, boolean accepted, long seekNonce) {}
