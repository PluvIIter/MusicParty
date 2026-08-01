package org.thornex.musicparty.service.stream;

import lombok.extern.slf4j.Slf4j;
import org.thornex.musicparty.config.AppProperties;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个直播流收听者（客户端连接）。
 * <p>
 * 每个客户端持有自己的有界缓冲队列与独立的泵线程（Java 21 虚拟线程）。
 * 广播线程只做非阻塞 {@link #offer(byte[])}，慢客户端造成的阻塞只发生在它自己的泵线程上，
 * 从而彻底隔离"一个慢客户端拖垮所有人"的头阻塞问题。
 * <p>
 * 缓冲满时采用 <b>drop-oldest</b> 策略：丢弃最旧音频、保持连接（VRChat 断线需手动重贴链接，不可接受；
 * MP3 是自同步帧编码，丢块后解码器会在毫秒级重新同步）。
 */
@Slf4j
public class StreamClient implements AutoCloseable {

    private final String id;
    private final String clientIp;
    private final StreamSink sink;
    private final ArrayBlockingQueue<byte[]> queue;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 该客户端的 IP 是否已被计入 unique-IP 收听数。
     * 由 {@link LiveStreamService} 在 addListener/removeListener 中读写，保证清理逻辑恰好执行一次。
     */
    final AtomicBoolean ipCounted = new AtomicBoolean(false);

    private final Thread pump;

    public StreamClient(StreamSink sink, String clientIp, AppProperties.StreamConfig config) {
        this(sink, clientIp, config, true);
    }

    /** 测试用：startPump=false 时不启动泵线程，便于确定性验证 drop-oldest。 */
    StreamClient(StreamSink sink, String clientIp, AppProperties.StreamConfig config, boolean startPump) {
        this.id = UUID.randomUUID().toString();
        this.clientIp = clientIp;
        this.sink = sink;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, config.getBufferChunks()));
        if (startPump) {
            this.pump = Thread.ofVirtual()
                    .name("stream-pump-" + id)
                    .start(this::pumpLoop);
        } else {
            this.pump = null;
        }
    }

    public String getId() {
        return id;
    }

    public String getClientIp() {
        return clientIp;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 非阻塞加入一块音频数据。队列满时丢弃最旧数据（drop-oldest），保证永不移除客户端。
     * 广播线程可安全调用，永不阻塞。
     *
     * @return false 表示客户端已关闭，调用方应将其从广播器移除
     */
    public boolean offer(byte[] chunk) {
        if (closed.get()) {
            return false;
        }
        while (!queue.offer(chunk)) {
            queue.poll();
        }
        return !closed.get();
    }

    /** 清空缓冲（暂停 / 切歌 / seek 时丢弃过期音频）。 */
    public void flush() {
        queue.clear();
    }

    /** 当前缓冲的块数（测试/诊断用）。 */
    public int getBufferedChunks() {
        return queue.size();
    }

    /** 当前缓冲中的块内容（测试/诊断用）。 */
    byte[][] snapshot() {
        return queue.toArray(new byte[0][]);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            sink.complete();
            Thread p = pump;
            if (p != null) {
                p.interrupt();
            }
        }
    }

    private void pumpLoop() {
        try {
            while (!closed.get()) {
                byte[] data = queue.take();
                if (closed.get()) {
                    break;
                }
                try {
                    sink.send(data);
                } catch (IOException | IllegalStateException e) {
                    // 客户端断开 / 响应已结束 → 终止本客户端
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            close();
        }
    }
}
