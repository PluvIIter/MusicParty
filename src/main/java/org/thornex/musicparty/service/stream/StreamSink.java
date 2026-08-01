package org.thornex.musicparty.service.stream;

import java.io.IOException;

/**
 * 单个直播流客户端的传输抽象。
 * <p>
 * 将底层响应通道（当前为 {@link ResponseBodyEmitter}）隔离出来，
 * 使 {@link StreamClient} 的核心逻辑（缓冲、泵线程、drop-oldest）可脱离容器进行单元测试。
 */
public interface StreamSink {

    /**
     * 向客户端写入一块音频数据。实现方负责阻塞语义：
     * 慢客户端导致的阻塞只会发生在各自的泵线程上，不会影响广播线程。
     */
    void send(byte[] data) throws IOException;

    /**
     * 结束输出（等价于响应正常完成）。须幂等。
     */
    void complete();
}
