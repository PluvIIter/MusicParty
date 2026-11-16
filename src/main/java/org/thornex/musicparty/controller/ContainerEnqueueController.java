package org.thornex.musicparty.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.thornex.musicparty.service.ContainerImportService;

@Controller
@RequiredArgsConstructor
public class ContainerEnqueueController {
    private final ContainerImportService imports;
    private final SimpMessagingTemplate messages;

    @MessageMapping("/enqueue/container")
    public void enqueue(ContainerImportService.Request request, @Header("simpSessionId") String sessionId) {
        imports.enqueue(request, sessionId).subscribe(result -> {
            var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            headers.setSessionId(sessionId);
            headers.setLeaveMutable(true);
            messages.convertAndSendToUser(sessionId, "/queue/container/import", result, headers.getMessageHeaders());
        });
    }
}
