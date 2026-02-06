package org.thornex.musicparty.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 当直播流监听状态发生变化时触发
 */
@Getter
public class StreamStatusEvent extends ApplicationEvent {
    private final boolean hasListeners;
    private final int listenerCount;

    public StreamStatusEvent(Object source, boolean hasListeners, int listenerCount) {
        super(source);
        this.hasListeners = hasListeners;
        this.listenerCount = listenerCount;
    }
}
