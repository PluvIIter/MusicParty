package org.thornex.musicparty.service.stream;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 直播流广播核心的单元测试：
 * <ul>
 *   <li>慢客户端不阻塞广播线程、不影响其他客户端（HOL 隔离）；</li>
 *   <li>缓冲满时 drop-oldest 保持连接；</li>
 *   <li>removeClient 幂等，回调恰好一次。</li>
 * </ul>
 */
class StreamBroadcasterTest {

    /** 假 StreamSink：可阻塞模拟慢客户端，可计数模拟正常客户端。 */
    static class RecordingSink implements StreamSink {
        final List<byte[]> received = new CopyOnWriteArrayList<>();
        /** 非空时，send 阻塞直到其计数归零（模拟慢/卡住的客户端）。 */
        CountDownLatch gate;
        /** 非空时，每次成功 send 后计数一次（用于确定性等待）。 */
        CountDownLatch delivered;

        @Override
        public void send(byte[] data) throws IOException {
            if (gate != null) {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            received.add(data);
            if (delivered != null) {
                delivered.countDown();
            }
        }

        @Override
        public void complete() {
            // no-op
        }
    }

    private static AppProperties.StreamConfig config(int bufferChunks) {
        AppProperties.StreamConfig cfg = new AppProperties.StreamConfig();
        cfg.setBufferChunks(bufferChunks);
        return cfg;
    }

    @Test
    void slowClientDoesNotBlockBroadcastOrOthers() throws Exception {
        StreamBroadcaster broadcaster = new StreamBroadcaster();
        CountDownLatch releaseSlow = new CountDownLatch(1);

        // 慢客户端：send 阻塞直到释放
        RecordingSink slowSink = new RecordingSink();
        slowSink.gate = releaseSlow;
        StreamClient slow = new StreamClient(slowSink, "1.2.3.4", config(32), true);

        // 正常客户端：send 后立刻通知
        RecordingSink fastSink = new RecordingSink();
        fastSink.delivered = new CountDownLatch(1);
        StreamClient fast = new StreamClient(fastSink, "5.6.7.8", config(32), true);

        broadcaster.addClient(slow);
        broadcaster.addClient(fast);

        byte[] chunk = new byte[]{1, 2, 3, 4};
        broadcaster.broadcast(chunk); // 返回即非阻塞

        // 慢客户端仍阻塞在 send 中，正常客户端已收到数据
        assertTrue(fastSink.delivered.await(2, TimeUnit.SECONDS), "正常客户端应收到数据");
        assertFalse(slow.isClosed(), "慢客户端不应被断开");
        assertEquals(2, broadcaster.getClientCount());

        // 释放慢客户端，让其泵线程退出，避免测试线程泄漏
        releaseSlow.countDown();
        slow.close();
        fast.close();
    }

    @Test
    void overflowDropsOldestAndKeepsConnection() {
        StreamClient client = new StreamClient(new RecordingSink(), "1.2.3.4", config(3), false);
        byte[] c1 = {1};
        byte[] c2 = {2};
        byte[] c3 = {3};
        byte[] c4 = {4};

        assertTrue(client.offer(c1));
        assertTrue(client.offer(c2));
        assertTrue(client.offer(c3));
        assertTrue(client.offer(c4)); // 队列已满 → 丢弃最旧的 c1

        assertFalse(client.isClosed(), "drop-oldest 不应断开连接");
        assertEquals(3, client.getBufferedChunks(), "缓冲应保持有界");
        assertArrayEquals(new byte[]{2}, client.snapshot()[0]);
        assertArrayEquals(new byte[]{3}, client.snapshot()[1]);
        assertArrayEquals(new byte[]{4}, client.snapshot()[2]);
        client.close();
    }

    @Test
    void removeClientIsIdempotentAndCallbackFiresOnce() {
        StreamBroadcaster broadcaster = new StreamBroadcaster();
        AtomicInteger removals = new AtomicInteger(0);
        broadcaster.setOnClientRemoved(c -> removals.incrementAndGet());

        StreamClient client = new StreamClient(new RecordingSink(), "1.2.3.4", config(8), true);

        broadcaster.addClient(client);
        assertTrue(broadcaster.removeClient(client));
        assertFalse(broadcaster.removeClient(client)); // 第二次幂等
        assertEquals(1, removals.get(), "回调应恰好触发一次");
        assertEquals(0, broadcaster.getClientCount());

        client.close();
    }

    @Test
    void closedClientIsCleanedUpOnBroadcast() {
        StreamBroadcaster broadcaster = new StreamBroadcaster();
        AtomicInteger removals = new AtomicInteger(0);
        broadcaster.setOnClientRemoved(c -> removals.incrementAndGet());

        StreamClient client = new StreamClient(new RecordingSink(), "1.2.3.4", config(8), true);
        broadcaster.addClient(client);
        client.close();

        broadcaster.broadcast(new byte[]{1, 2, 3});
        assertEquals(0, broadcaster.getClientCount(), "已关闭客户端应在广播时被清理");
        assertEquals(1, removals.get());

        client.close(); // 幂等，无副作用
    }

    @Test
    void flushClearsBufferedAudio() {
        StreamClient client = new StreamClient(new RecordingSink(), "1.2.3.4", config(3), false);
        client.offer(new byte[]{1});
        client.offer(new byte[]{2});
        assertEquals(2, client.getBufferedChunks());

        client.flush();
        assertEquals(0, client.getBufferedChunks());
        assertTrue(Arrays.equals(client.snapshot(), new byte[0][]));
        client.close();
    }
}
