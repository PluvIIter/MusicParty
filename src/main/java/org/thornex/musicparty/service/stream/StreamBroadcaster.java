package org.thornex.musicparty.service.stream;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 负责将音频数据分发给多个 HTTP 连接
 */
@Slf4j
public class StreamBroadcaster {

    private final Set<OutputStream> clients = new CopyOnWriteArraySet<>();
    
    // 简单的静音帧 (MP3 Header + Silence data)，这里仅作占位示意
    // 实际生产中最好用 FFmpeg 生成一段静音流，或者简单地什么都不发(客户端会缓冲)
    // 为防止客户端超时断开，发送空字节在某些播放器上可能有效，在 MP3 流中可能导致杂音
    // 更好的做法是保持 Socket 打开但不发送数据，直到有新数据
    
    public void addClient(OutputStream os) {
        clients.add(os);
        log.info("Stream client connected. Total: {}", clients.size());
    }

    public void removeClient(OutputStream os) {
        clients.remove(os);
        log.info("Stream client disconnected. Total: {}", clients.size());
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
