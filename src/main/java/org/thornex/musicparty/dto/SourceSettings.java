package org.thornex.musicparty.dto;

import org.thornex.musicparty.source.DeliveryMode;

public record SourceSettings(Boolean enabled, DeliveryMode deliveryMode) {}
