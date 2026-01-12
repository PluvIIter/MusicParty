package org.thornex.musicparty.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 当在线用户数量发生变化时触发
 */
@Getter
public class UserCountChangeEvent extends ApplicationEvent {
    private final int onlineUserCount;

    public UserCountChangeEvent(Object source, int onlineUserCount) {
        super(source);
        this.onlineUserCount = onlineUserCount;
    }
}