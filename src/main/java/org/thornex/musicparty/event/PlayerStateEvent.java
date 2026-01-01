package org.thornex.musicparty.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import org.thornex.musicparty.dto.PlayerState;

/**
 * 当播放器状态（播放/暂停/切歌/进度）发生变化时触发
 */
@Getter
public class PlayerStateEvent extends ApplicationEvent {
    private final PlayerState state;

    public PlayerStateEvent(Object source, PlayerState state) {
        super(source);
        this.state = state;
    }
}