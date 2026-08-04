package org.thornex.musicparty.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.PrivateDjSegment;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

class PrivateDjServiceTest {

    private final ObjectMapper om = new ObjectMapper();
    private AppProperties props;
    private NeteaseMusicApiService api;
    private PrivateDjService service;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        api = mock(NeteaseMusicApiService.class);
        service = new PrivateDjService(api, props);
    }

    private static final String FM_JSON = """
        {"data":[
          {"id":1981999919,"name":"相爱就是说了100次对不起","duration":264727,
           "artists":[{"name":"Crispy脆乐团"}],
           "album":{"name":"爱是我们必经的辛苦","picUrl":"http://x/pic.jpg"}},
          {"id":22425068,"name":"Nervous Breakdown","duration":180000,
           "artists":[{"name":"Aa"}],"album":{}}
        ]}""";

    private static final String DJ_JSON = """
        {"data":{"tagName":"","aiDjResources":[
          {"type":"audio","value":{"audioList":[{"audioId":"prompt_song_x","audioUrl":"http://aidj/x.mp3","duration":15.1,"introductionRelatedSongIds":[3597639]}]}},
          {"type":"song","value":{"songId":3597639,"songData":{"id":3597639,"name":"Run, Run, Run","duration":240000,"artists":[{"name":"The Real Group"}],"album":{"name":"FILOU","picUrl":"http://cover/run.jpg"}}}},
          {"type":"song","value":{"songId":3389900618,"songData":{"id":3389900618,"name":"你又躲","duration":200000,"artists":[{"name":"S1ent"}]}}},
          {"type":"audio","value":{"audioList":[{"audioId":"prompt_song_y","audioUrl":"http://aidj/y.mp3","duration":15.5,"introductionRelatedSongIds":[438204707]}]}},
          {"type":"song","value":{"songId":438204707,"songData":{"id":438204707,"name":"天若有情","duration":250000,"artists":[{"name":"黄丽玲"}]}}}
        ]}}""";

    private JsonNode parse(String json) throws Exception { return om.readTree(json); }

    @Test
    void fmBatchYieldsSongsInOrderThenRefills() throws Exception {
        props.getPrivateDj().setMode("FM");
        when(api.fetchPersonalFm()).thenReturn(Mono.just(parse(FM_JSON)));
        PrivateDjSegment s1 = service.nextSegment().block();
        assertInstanceOf(PrivateDjSegment.Song.class, s1);
        assertEquals("1981999919", ((PrivateDjSegment.Song) s1).songId());
        assertEquals("相爱就是说了100次对不起", ((PrivateDjSegment.Song) s1).name());
        assertEquals(264727L, ((PrivateDjSegment.Song) s1).durationMs());
        assertEquals("http://x/pic.jpg", ((PrivateDjSegment.Song) s1).coverUrl());

        PrivateDjSegment s2 = service.nextSegment().block();
        assertEquals("22425068", ((PrivateDjSegment.Song) s2).songId());

        // 批次耗尽后自动再拉
        when(api.fetchPersonalFm()).thenReturn(Mono.just(parse(FM_JSON)));
        PrivateDjSegment s3 = service.nextSegment().block();
        assertEquals("1981999919", ((PrivateDjSegment.Song) s3).songId());
        verify(api, times(2)).fetchPersonalFm();
    }

    @Test
    void djBatchYieldsVoiceThenSongsInArrayOrder() throws Exception {
        props.getPrivateDj().setMode("DJ");
        when(api.fetchAidjRcmd(nullable(Double.class), nullable(Double.class))).thenReturn(Mono.just(parse(DJ_JSON)));
        PrivateDjSegment v1 = service.nextDjSegment().block();
        assertInstanceOf(PrivateDjSegment.Voice.class, v1);
        assertEquals("http://aidj/x.mp3", ((PrivateDjSegment.Voice) v1).voiceUrl());
        assertEquals(15100L, ((PrivateDjSegment.Voice) v1).durationMs());
        assertEquals("3597639", ((PrivateDjSegment.Voice) v1).relatedSongId());
        assertEquals("http://cover/run.jpg", ((PrivateDjSegment.Voice) v1).relatedCoverUrl());

        PrivateDjSegment s1 = service.nextDjSegment().block();
        assertInstanceOf(PrivateDjSegment.Song.class, s1);
        assertEquals("3597639", ((PrivateDjSegment.Song) s1).songId());

        PrivateDjSegment s2 = service.nextDjSegment().block();
        assertEquals("3389900618", ((PrivateDjSegment.Song) s2).songId());

        PrivateDjSegment v2 = service.nextDjSegment().block();
        assertInstanceOf(PrivateDjSegment.Voice.class, v2);
        assertEquals("http://aidj/y.mp3", ((PrivateDjSegment.Voice) v2).voiceUrl());
    }

    @Test
    void nextFmSegmentForcesFmRegardlessOfMode() throws Exception {
        props.getPrivateDj().setMode("DJ"); // 模式是 DJ，但强制取 FM
        when(api.fetchPersonalFm()).thenReturn(Mono.just(parse(FM_JSON)));
        when(api.fetchAidjRcmd(any(), any())).thenReturn(Mono.just(parse(DJ_JSON)));
        PrivateDjSegment seg = service.nextFmSegment().block();
        assertInstanceOf(PrivateDjSegment.Song.class, seg);
        assertEquals("1981999919", ((PrivateDjSegment.Song) seg).songId());
        verify(api, never()).fetchAidjRcmd(any(), any());
    }

    @Test
    void invalidateClearsBatches() throws Exception {
        props.getPrivateDj().setMode("FM");
        when(api.fetchPersonalFm()).thenReturn(Mono.just(parse(FM_JSON)));
        service.nextSegment().block();
        service.invalidate();
        when(api.fetchPersonalFm()).thenReturn(Mono.just(parse(FM_JSON)));
        service.nextSegment().block();
        verify(api, times(2)).fetchPersonalFm();
    }
}
