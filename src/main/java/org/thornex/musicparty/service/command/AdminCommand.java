package org.thornex.musicparty.service.command;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.thornex.musicparty.dto.PlayerEvent;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.enums.PlayerAction;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminCommand implements ChatCommand {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public String getCommand() {
        return "admin";
    }

    @Override
    public void execute(String args, User user) {
        log.info("Admin command triggered by user: {} (Session: {})", user.getName(), user.getSessionId());
        
        PlayerEvent event = new PlayerEvent(
                "INFO",
                PlayerAction.ADMIN_TRIGGER.name(),
                user.getToken(),
                "OPEN_ADMIN_MODAL",
                null
        );

        messagingTemplate.convertAndSendToUser(
                user.getSessionId(),
                "/queue/events",
                event,
                createSessionHeaders(user.getSessionId())
        );
        log.debug("Sent ADMIN_TRIGGER event to user: {}", user.getSessionId());
    }

    private MessageHeaders createSessionHeaders(String sessionId) {
        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headerAccessor.setSessionId(sessionId);
        headerAccessor.setLeaveMutable(true);
        return headerAccessor.getMessageHeaders();
    }
}
