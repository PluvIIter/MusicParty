package org.thornex.musicparty.service;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.dto.ChatMessage;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.enums.MessageType;
import org.thornex.musicparty.enums.PlayerAction;
import org.thornex.musicparty.event.SystemMessageEvent;
import org.thornex.musicparty.config.AppProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class ChatService {

    // 使用并发双端队列存储消息
    private final ConcurrentLinkedDeque<ChatMessage> history = new ConcurrentLinkedDeque<>();

    private final SimpMessagingTemplate messagingTemplate;
    private final UserService userService;
    private final AppProperties appProperties;

    public ChatService(SimpMessagingTemplate messagingTemplate, UserService userService, AppProperties appProperties) {
        this.messagingTemplate = messagingTemplate;
        this.userService = userService;
        this.appProperties = appProperties;
    }

    public void addMessage(ChatMessage message) {
        history.addLast(message);
        if (history.size() > appProperties.getChat().getMaxHistorySize()) {
            history.removeFirst();
        }
    }

    /**
     * 分页获取历史记录 (从最新往旧推)
     * @param offset 跳过最近的多少条
     * @param limit 取多少条
     */
    public List<ChatMessage> getHistory(int offset, int limit) {
        // 我们将其转为 List 进行倒序切片处理
        List<ChatMessage> snapshot = new ArrayList<>(history);
        Collections.reverse(snapshot);

        if (offset >= snapshot.size()) {
            return Collections.emptyList();
        }

        int end = Math.min(offset + limit, snapshot.size());
        List<ChatMessage> page = snapshot.subList(offset, end);

        Collections.reverse(page);
        return page;
    }


    public void clearHistory() {
        history.clear();
    }

    /**
     * 监听系统事件，自动生成系统消息
     * 这里实现了将“操作日志”写入“系统聊天Tab”的需求
     */
    @EventListener
    public void onSystemEvent(SystemMessageEvent event) {
        // 忽略错误提示，只记录操作成功的事件（根据需求调整）
        // 这里我们记录所有 INFO, WARN, SUCCESS 级别的事件，忽略 ERROR (通常 ERROR 只弹 Toast)
        if (event.getLevel() == SystemMessageEvent.Level.ERROR) return;

        // 如果是 RESET 事件，清空历史
        if (event.getAction() == PlayerAction.RESET) {
            clearHistory();
            // 依然发送一条“系统已重置”的消息作为新历史的开始
        }

        String userName = "SYSTEM";
        if (!"SYSTEM".equals(event.getUserId())) {
            userName = userService.getUserByToken(event.getUserId())
                    .map(User::getName)
                    .orElse("Unknown");
        }

        String content = generateSystemMessageContent(event,userName);
        MessageType type = event.getAction() == PlayerAction.LIKE ? MessageType.LIKE : MessageType.SYSTEM;

        String msgUserId = event.getAction() == PlayerAction.LIKE ? event.getUserId() : "SYSTEM";
        String msgUserName = event.getAction() == PlayerAction.LIKE ? userName : "SYSTEM";

        ChatMessage sysMsg = new ChatMessage(
                UUID.randomUUID().toString(),
                msgUserId,
                msgUserName,
                content,
                System.currentTimeMillis(),
                type
        );

        // 1. 存入历史
        addMessage(sysMsg);

        // 2. 广播到聊天频道
        messagingTemplate.convertAndSend("/topic/chat", sysMsg);
    }

    // 辅助：生成友好的系统消息文案
    private String generateSystemMessageContent(SystemMessageEvent event, String userName) {
        String payload = event.getPayload() == null ? "" : event.getPayload();

        return switch (event.getAction()) {
            case PLAY -> userName + " 恢复了播放";
            case PAUSE -> userName + " 暂停了播放";
            case SKIP -> userName + " 切到了下一首";
            case ADD -> userName + " 添加了: " + payload;
            case REMOVE -> userName + " 移除了: " + payload;
            case TOP -> userName + " 置顶了: " + payload;
            case LIKE -> userName + " 觉得 " + payload + " 很赞";
            case IMPORT_PLAYLIST -> userName + " 导入了歌单 (" + payload + "首)";
            case SHUFFLE_ON -> userName + " 开启了随机播放";
            case SHUFFLE_OFF -> userName + " 关闭了随机播放";
            case RESET -> "系统已被重置";
            default -> userName + " 执行操作: " + event.getAction();
        };
    }
}