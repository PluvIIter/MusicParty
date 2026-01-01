package org.thornex.musicparty.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import org.thornex.musicparty.dto.MusicQueueItem;

import java.util.List;

/**
 * 当队列内容发生变化（增删改、排序、状态变更）时触发
 */
@Getter
public class QueueUpdateEvent extends ApplicationEvent {
    private final List<MusicQueueItem> queue;

    public QueueUpdateEvent(Object source, List<MusicQueueItem> queue) {
        super(source);
        this.queue = queue;
    }
}