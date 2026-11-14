package org.thornex.musicparty.service.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按原歌词时间轴合并译文歌词.
 *
 * <p>网易云的 {@code tlyric} 与 QQ 音乐的 {@code trans} 都是与原歌词同时间轴的 LRC，
 * 这里把同一时间点的译文追加到原文行尾，前端只需要继续按单行 LRC 渲染。</p>
 */
final class LyricTranslations {
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern TIMESTAMP_PREFIX = Pattern.compile("^(?:\\[[^\\]]*\\])+");
    private static final String SEPARATOR = " ／ ";

    private LyricTranslations() { }

    static String merge(String lyric, String translation) {
        if (lyric == null || lyric.isBlank() || translation == null || translation.isBlank()) {
            return lyric == null ? "" : lyric;
        }
        Map<Long, String> translated = parse(translation);
        if (translated.isEmpty()) return lyric;
        String[] lines = lyric.split("\n", -1);
        StringBuilder merged = new StringBuilder(lyric.length() + translation.length());
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) merged.append('\n');
            String line = lines[index];
            merged.append(line);
            Long time = timestamp(line);
            if (time == null) continue;
            String text = translated.get(time);
            if (text == null || text.isBlank()) continue;
            String original = text(line);
            if (original.isEmpty() || original.equals(text)) continue;
            merged.append(SEPARATOR).append(text);
        }
        return merged.toString();
    }

    private static Map<Long, String> parse(String lrc) {
        Map<Long, String> translated = new LinkedHashMap<>();
        for (String line : lrc.split("\n")) {
            Long time = timestamp(line);
            if (time == null) continue;
            String text = text(line);
            if (!text.isEmpty()) translated.putIfAbsent(time, text);
        }
        return translated;
    }

    private static String text(String line) {
        return TIMESTAMP_PREFIX.matcher(line == null ? "" : line).replaceFirst("").trim();
    }

    private static Long timestamp(String line) {
        Matcher matcher = TIME.matcher(line == null ? "" : line);
        if (!matcher.find()) return null;
        long minutes = Long.parseLong(matcher.group(1));
        long seconds = Long.parseLong(matcher.group(2));
        String fraction = matcher.group(3);
        long millis = 0;
        if (fraction != null && !fraction.isEmpty()) {
            millis = switch (fraction.length()) {
                case 1 -> Long.parseLong(fraction) * 100;
                case 2 -> Long.parseLong(fraction) * 10;
                default -> Long.parseLong(fraction.substring(0, 3));
            };
        }
        return minutes * 60_000L + seconds * 1_000L + millis;
    }
}
