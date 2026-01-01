package org.thornex.musicparty.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import org.thornex.musicparty.enums.PlayerAction;

/**
 * 用于广播系统通知、错误提示或操作回执
 * 替代原有的 broadcastEvent 方法
 */
@Getter
public class SystemMessageEvent extends ApplicationEvent {

    public enum Level { INFO, WARN, ERROR, SUCCESS }

    private final Level level;
    private final PlayerAction action;
    private final String userId; // 触发者的 Token，可为 "SYSTEM"
    private final String payload; // 附加信息（如歌曲名）

    public SystemMessageEvent(Object source, Level level, PlayerAction action, String userId, String payload) {
        super(source);
        this.level = level;
        this.action = action;
        this.userId = userId;
        this.payload = payload;
    }
}