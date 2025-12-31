package org.thornex.musicparty.event;

import org.springframework.context.ApplicationEvent;

public class DownloadStatusEvent extends ApplicationEvent {
    private final String musicId;

    public DownloadStatusEvent(Object source, String musicId) {
        super(source);
        this.musicId = musicId;
    }
    public String getMusicId() { return musicId; }
}