package org.thornex.musicparty.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.dto.ChatMessage;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.enums.MessageType;
import org.thornex.musicparty.enums.PlayerAction;
import org.thornex.musicparty.event.SystemMessageEvent;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.service.command.ChatCommand;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ChatService {

    private final ConcurrentLinkedDeque<ChatMessage> history = new ConcurrentLinkedDeque<>();
    private final SimpMessagingTemplate messagingTemplate;
    private final UserService userService;
    private final AppProperties appProperties;
    
    private final Map<String, ChatCommand> commandMap;
    private final Map<String, Long> lastMessageTime = new java.util.concurrent.ConcurrentHashMap<>();

    public ChatService(SimpMessagingTemplate messagingTemplate, UserService userService, AppProperties appProperties, List<ChatCommand> commands) {
        this.messagingTemplate = messagingTemplate;
        this.userService = userService;
        this.appProperties = appProperties;
        this.commandMap = commands.stream().collect(Collectors.toMap(ChatCommand::getCommand, Function.identity()));
    }

    /**
     * 检查是否允许发送消息（频率限制）
     */
    public boolean canUserSendMessage(String userToken) {
        long now = System.currentTimeMillis();
        long last = lastMessageTime.getOrDefault(userToken, 0L);
        long minInterval = appProperties.getChat().getMinIntervalMs();

        if (now - last < minInterval) {
            return false;
        }

        lastMessageTime.put(userToken, now);
        return true;
    }

    /**
     * 检查消息长度
     */
    public boolean isMessageLengthValid(String content) {
        return content != null && content.length() <= appProperties.getChat().getMaxMessageLength();
    }

    /**
     * 处理传入的消息
     * @return true 如果消息被处理（不广播），false 如果应该继续广播
     */
    public boolean processIncomingMessage(String sessionId, String content) {
        if (content.startsWith("//")) {
            String fullCmd = content.substring(2).trim();
            String[] parts = fullCmd.split("\\s+", 2);
            String cmdKey = parts[0].toLowerCase();
            String args = parts.length > 1 ? parts[1] : "";

            ChatCommand handler = commandMap.get(cmdKey);
            if (handler != null) {
                userService.getUser(sessionId).ifPresent(user -> handler.execute(args, user));
                return true; // 拦截消息
            }
        }
        return false; // 普通消息，继续处理
    }

    public void addMessage(ChatMessage message) {
        history.addLast(message);
        if (history.size() > appProperties.getChat().getMaxHistorySize()) {
            history.removeFirst();
        }
    }
// ... existing code ...
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

    /**
     * 获取全部聊天记录用于持久化
     */
    public List<ChatMessage> getHistoryFull() {
        return new ArrayList<>(history);
    }

    /**
     * 恢复聊天记录
     */
    public void restore(List<ChatMessage> loadedHistory) {
        history.clear();
        if (loadedHistory != null) {
            history.addAll(loadedHistory);
        }
    }


    public void clearHistory() {
        history.clear();
    }

    public void clearHistoryAndNotify() {
        clearHistory();
        broadcastSystemMessage("聊天记录已由管理员清空");
    }

    private void broadcastSystemMessage(String content) {
        ChatMessage sysMsg = new ChatMessage(
                UUID.randomUUID().toString(),
                "SYSTEM",
                "SYSTEM",
                content,
                System.currentTimeMillis(),
                MessageType.SYSTEM
        );
        addMessage(sysMsg);
        messagingTemplate.convertAndSend("/topic/chat", sysMsg);
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

        String content = generateSystemMessageContent(event, userName);
        MessageType type;

        if (event.getAction() == PlayerAction.LIKE) {
            type = MessageType.LIKE;
        } else if (event.getAction() == PlayerAction.PLAY_START) {
            type = MessageType.PLAY_START;
        } else {
            type = MessageType.SYSTEM;
        }

        String msgUserId = (event.getAction() == PlayerAction.LIKE || event.getAction() == PlayerAction.PLAY_START) ? event.getUserId() : "SYSTEM";
        String msgUserName = (event.getAction() == PlayerAction.LIKE || event.getAction() == PlayerAction.PLAY_START) ? userName : "SYSTEM";

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
            case PLAY_START -> "开始播放来自于 " + userName + " 的 " + payload;
            case USER_JOIN -> userName + " 加入了派对";
            case USER_LEAVE -> userName + " 离开了派对";
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