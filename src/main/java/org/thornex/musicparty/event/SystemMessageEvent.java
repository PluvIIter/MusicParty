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
    private final String userName; // 发送者名字快照（可选）：入队时记录，防用户离线过期后现场查不到
    private final String payload; // 附加信息（如歌曲名）

    public SystemMessageEvent(Object source, Level level, PlayerAction action, String userId, String payload) {
        this(source, level, action, userId, null, payload);
    }

    public SystemMessageEvent(Object source, Level level, PlayerAction action, String userId, String userName, String payload) {
        super(source);
        this.level = level;
        this.action = action;
        this.userId = userId;
        this.userName = userName;
        this.payload = payload;
    }
}