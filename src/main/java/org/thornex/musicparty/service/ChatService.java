package org.thornex.musicparty.service;

import org.springframework.stereotype.Service;
import org.thornex.musicparty.dto.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class ChatService {

    private static final int HISTORY_LIMIT = 100;
    // 使用并发双端队列存储消息
    private final ConcurrentLinkedDeque<ChatMessage> history = new ConcurrentLinkedDeque<>();

    public void addMessage(ChatMessage message) {
        history.addLast(message);
        if (history.size() > HISTORY_LIMIT) {
            history.removeFirst();
        }
    }

    public List<ChatMessage> getHistory() {
        return new ArrayList<>(history);
    }

    public void clearHistory() {
        history.clear();
    }
}