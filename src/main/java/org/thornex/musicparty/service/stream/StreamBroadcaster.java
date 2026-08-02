package org.thornex.musicparty.service.stream;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.Deque;
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

    /**
     * 新客户端首连时预填的最近音频块数：约 8s。
     * <p>
     * 首连若从空队列开始，第一个字节要等下一个转码块（{@code -re} 实时 ≈1s 一块），
     * ResponseBodyEmitter 的响应头也随首个 send() 延迟 ~1s 才提交，VRC 等不及会断开重试
     * （实测首连出现 3 次 connect/disconnect、~10s 顿卡）。预填最近音频后：首字节/响应头
     * 毫秒级到达，且客户端自带一段初始缓冲，播放器不会欠缓冲。
     */
    private static final int RECENT_CHUNKS_FOR_NEW_CLIENT = 8;

    private final Set<StreamClient> clients = ConcurrentHashMap.newKeySet();
    /** 最近广播的音频块环形缓冲：无监听者时也持续记录，供新客户端首连立即拿到数据、立即提交响应头 */
    private final Deque<byte[]> recentChunks = new ArrayDeque<>();
    private volatile Consumer<StreamClient> onClientRemoved;

    public void setOnClientRemoved(Consumer<StreamClient> onClientRemoved) {
        this.onClientRemoved = onClientRemoved;
    }

    public void addClient(StreamClient client) {
        // 先预填最近音频再加入集合：保证首连立即有数据（响应头随之立即提交），
        // 且队列顺序为"最近→实时"，不会出现旧音频排在新音频之后的乱序。
        synchronized (recentChunks) {
            for (byte[] chunk : recentChunks) {
                client.offer(chunk);
            }
        }
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
     * <p>
     * 环形缓冲在<b>无监听者时也持续记录</b>（热转码 0 连接场景），这样第一个听众加入时
     * 立即有最近音频可预填，而不必等下一个转码块。
     */
    public void broadcast(byte[] chunk) {
        synchronized (recentChunks) {
            recentChunks.addLast(chunk);
            while (recentChunks.size() > RECENT_CHUNKS_FOR_NEW_CLIENT) {
                recentChunks.pollFirst();
            }
        }
        if (clients.isEmpty()) {
            return;
        }
        for (StreamClient client : clients) {
            if (!client.offer(chunk)) {
                removeClient(client);
            }
        }
    }

    /** 清空所有客户端的缓冲与环形缓冲（暂停 / 切歌 / seek 时丢弃过期音频，避免新听众被预填旧歌音频）。 */
    public void flushAll() {
        synchronized (recentChunks) {
            recentChunks.clear();
        }
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
