package org.thornex.musicparty.dto;

public record AdminPrivateDjUpdateRequest(
        String mode,
        Boolean fillBlankEnabled,
        Boolean joinQueueEnabled,
        Boolean custodyEnabled
) {}
