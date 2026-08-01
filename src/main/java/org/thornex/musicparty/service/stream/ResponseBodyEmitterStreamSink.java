package org.thornex.musicparty.service.stream;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;

/**
 * 基于 {@link ResponseBodyEmitter} 的 {@link StreamSink} 实现。
 * <p>
 * 连接生命周期（断开/超时/错误）由容器通过 onCompletion/onTimeout/onError 回调管理，
 * 这里只负责把音频块写进异步响应。
 */
public class ResponseBodyEmitterStreamSink implements StreamSink {

    private static final MediaType AUDIO_MPEG = MediaType.parseMediaType("audio/mpeg");

    private final ResponseBodyEmitter emitter;

    public ResponseBodyEmitterStreamSink(ResponseBodyEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void send(byte[] data) throws IOException {
        emitter.send(data, AUDIO_MPEG);
    }

    @Override
    public void complete() {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 响应可能已结束，忽略
        }
    }
}
