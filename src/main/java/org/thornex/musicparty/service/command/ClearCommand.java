package org.thornex.musicparty.service.command;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.enums.PlayerAction;
import org.thornex.musicparty.event.SystemMessageEvent;
import org.thornex.musicparty.service.MusicQueueManager;
import org.thornex.musicparty.service.MusicPlayerService;

@Component
@RequiredArgsConstructor
public class ClearCommand implements ChatCommand {

    private final MusicQueueManager queueManager;
    private final MusicPlayerService musicPlayerService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public String getCommand() {
        return "clear";
    }

    @Override
    public void execute(String args, User user) {
        int count = queueManager.removeByUser(user.getToken());
        
        if (count > 0) {
            // 发送系统通知 (聊天栏)
            eventPublisher.publishEvent(new SystemMessageEvent(
                    this, 
                    SystemMessageEvent.Level.INFO, 
                    PlayerAction.REMOVE, 
                    user.getToken(), 
                    String.format("清空了自己点的 %d 首歌", count)
            ));
            
            // 广播队列更新
            musicPlayerService.broadcastQueueUpdate();
        } else {
            // 如果没歌，不用特意发通知，或者发一个私有通知
            // 这里我们保持简单，不产生任何动作
        }
    }
}
