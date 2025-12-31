package org.thornex.musicparty.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class DownloadStatusEvent extends ApplicationEvent {
    private final String musicId;

    public DownloadStatusEvent(Object source, String musicId) {
        super(source);
        this.musicId = musicId;
    }
}