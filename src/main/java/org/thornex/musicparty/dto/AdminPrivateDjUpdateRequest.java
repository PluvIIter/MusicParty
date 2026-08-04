package org.thornex.musicparty.dto;

public record AdminPrivateDjUpdateRequest(
        Boolean masterEnabled,
        String mode,
        Boolean fillBlankEnabled,
        Boolean joinQueueEnabled,
        Boolean custodyEnabled
) {}
