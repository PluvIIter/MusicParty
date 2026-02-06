package org.thornex.musicparty.service.stream;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * 负责将音频数据分发给多个 HTTP 连接
 */
@Slf4j
public class StreamBroadcaster {

    private final Set<OutputStream> clients = new CopyOnWriteArraySet<>();
    private Consumer<OutputStream> onClientRemoved;

    public void setOnClientRemoved(Consumer<OutputStream> onClientRemoved) {
        this.onClientRemoved = onClientRemoved;
    }
    
    public void addClient(OutputStream os) {
        clients.add(os);
        log.info("Stream client connected. Total: {}", clients.size());
    }

    public void removeClient(OutputStream os) {
        if (clients.remove(os)) {
            log.info("Stream client disconnected. Total: {}", clients.size());
            if (onClientRemoved != null) {
                onClientRemoved.accept(os);
            }
        }
    }

    public int getClientCount() {
        return clients.size();
    }

    public void broadcast(byte[] data, int length) {
        if (clients.isEmpty()) return;

        for (OutputStream client : clients) {
            try {
                client.write(data, 0, length);
                client.flush();
            } catch (IOException e) {
                // 客户端断开
                removeClient(client);
            }
        }
    }
    
    public void sendSilence() {
        // Implementation dependent:
        // 如果客户端是 VRChat，不发送数据通常会导致它暂停等待缓冲
        // 如果发送全0数据，MP3解码器可能会报错或产生噪音
        // 最佳实践：当没有数据时，什么都不做，让 ffmpeg 进程结束后自然停止写入，
        // 或者让 LiveStreamService 在空闲时挂起一个生成静音的 ffmpeg 进程。
    }
}
