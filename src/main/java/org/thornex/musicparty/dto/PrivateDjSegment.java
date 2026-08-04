package org.thornex.musicparty.dto;

import java.util.List;

/** 私人FM/DJ 的一段可播放内容：歌曲或 DJ 语音。 */
public sealed interface PrivateDjSegment {
    /** 一首真实歌曲（FM 或 DJ 推荐歌曲） */
    record Song(String songId, String name, List<String> artists,
                long durationMs, String coverUrl) implements PrivateDjSegment {}

    /** 一段 DJ 语音（voiceUrl 为可直接播放的 mp3 直链；relatedCoverUrl 为被点评歌曲的封面） */
    record Voice(String voiceUrl, String voiceId, long durationMs,
                 String relatedSongId, String relatedCoverUrl) implements PrivateDjSegment {}
}
