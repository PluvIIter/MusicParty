package org.thornex.musicparty.service.stream;

import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 将共享的音频流分发给所有 HTTP 收听者。
 * <p>
 * 分发是<b>非阻塞</b>的：只向每个 {@link StreamClient} 的缓冲队列做 {@code offer}，
 * 慢客户端造成的阻塞只发生在它自己的泵线程上，不会拖慢广播线程或影响其他客户端。
 */
@Slf4j
public class StreamBroadcaster {

    private final Set<StreamClient> clients = ConcurrentHashMap.newKeySet();
    private volatile Consumer<StreamClient> onClientRemoved;

    public void setOnClientRemoved(Consumer<StreamClient> onClientRemoved) {
        this.onClientRemoved = onClientRemoved;
    }

    public void addClient(StreamClient client) {
        clients.add(client);
        log.info("Stream client connected. Total: {}", clients.size());
    }

    /**
     * 移除客户端。仅在确实从集合中移除时触发一次 {@code onClientRemoved} 回调
     * （配合 {@link StreamClient#ipCounted} 保证清理逻辑恰好执行一次）。
     *
     * @return true 表示该客户端此前在集合中、本次被移除；false 表示本就不存在（幂等）
     */
    public boolean removeClient(StreamClient client) {
        if (clients.remove(client)) {
            log.info("Stream client disconnected. Total: {}", clients.size());
            Consumer<StreamClient> callback = onClientRemoved;
            if (callback != null) {
                callback.accept(client);
            }
            return true;
        }
        return false;
    }

    public int getClientCount() {
        return clients.size();
    }

    /**
     * 将一块音频分发给所有客户端。非阻塞：只做 {@code offer}，永不失败、永不移除（drop-oldest）。
     * 已关闭的客户端会被顺带清理。
     */
    public void broadcast(byte[] chunk) {
        if (clients.isEmpty()) {
            return;
        }
        for (StreamClient client : clients) {
            if (!client.offer(chunk)) {
                removeClient(client);
            }
        }
    }

    /** 清空所有客户端的缓冲（暂停 / 切歌 / seek 时丢弃过期音频）。 */
    public void flushAll() {
        clients.forEach(StreamClient::flush);
    }

    /** 关闭并移除所有客户端（服务关闭时调用）。 */
    public void closeAll() {
        for (StreamClient client : clients) {
            client.close();
            removeClient(client);
        }
    }
}
