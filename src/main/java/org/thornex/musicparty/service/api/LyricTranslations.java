package org.thornex.musicparty.service.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按原歌词时间轴合并译文歌词.
 *
 * <p>网易云的 {@code tlyric} 与 QQ 音乐的 {@code trans} 都是与原歌词同时间轴的 LRC。
 * 合并结果仍是纯 LRC：每一句原文后面紧跟一句同时间戳的译文，前端按单行 LRC 解析即可
 * 把译文显示成独立的一行（原文在上、译文在下），不需要改动解析器。</p>
 */
final class LyricTranslations {
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern TIMESTAMP_PREFIX = Pattern.compile("^(?:\\[[^\\]]*\\])+");
    /** 译文与原文时间戳允许的漂移：上游偶尔少/多几十毫秒。 */
    private static final long MAX_DRIFT_MS = 300;

    private record Line(long time, String text) { }

    private LyricTranslations() { }

    static String merge(String lyric, String translation) {
        if (lyric == null || lyric.isBlank()) return lyric == null ? "" : lyric;
        if (translation == null || translation.isBlank()) return lyric;
        List<Line> translated = parse(translation);
        if (translated.isEmpty()) return lyric;

        List<String> output = new ArrayList<>();
        boolean[] used = new boolean[translated.size()];
        List<Line> pending = new ArrayList<>();
        for (String raw : lyric.split("\\R")) {
            List<Long> sourceTimes = timestamps(raw);
            if (!sourceTimes.isEmpty()) flushBefore(output, pending, sourceTimes.get(0));
            output.add(raw);
            String original = text(raw);
            if (original.isEmpty()) continue;
            for (long time : sourceTimes) {
                int match = nearest(translated, used, time);
                if (match < 0) continue;
                used[match] = true;
                String translatedText = translated.get(match).text();
                if (translatedText.equals(original)) continue;
                pending.add(new Line(time, translatedText));
            }
            if (!sourceTimes.isEmpty()) flushBefore(output, pending, sourceTimes.get(0));
        }
        flushBefore(output, pending, Long.MAX_VALUE);
        return String.join("\n", output);
    }

    private static void flushBefore(List<String> output, List<Line> pending, long nextTime) {
        pending.sort(java.util.Comparator.comparingLong(Line::time));
        while (!pending.isEmpty() && pending.get(0).time() <= nextTime) {
            Line line = pending.remove(0);
            output.add(format(line.time()) + line.text());
        }
    }

    /** 找到与原文时间最接近、且尚未被使用的译文；超出漂移上限视为没有译文。 */
    private static int nearest(List<Line> translated, boolean[] used, long time) {
        int best = -1;
        long distance = MAX_DRIFT_MS + 1;
        for (int index = 0; index < translated.size(); index++) {
            if (used[index]) continue;
            long diff = Math.abs(translated.get(index).time() - time);
            if (diff < distance) {
                distance = diff;
                best = index;
            }
        }
        return distance <= MAX_DRIFT_MS ? best : -1;
    }

    private static List<Line> parse(String lrc) {
        List<Line> lines = new ArrayList<>();
        for (String raw : lrc.split("\\R")) {
            String text = text(raw);
            if (!text.isEmpty()) for (long time : timestamps(raw)) lines.add(new Line(time, text));
        }
        return lines;
    }

    private static String text(String line) {
        return TIMESTAMP_PREFIX.matcher(line == null ? "" : line).replaceFirst("").trim();
    }

    private static String format(long millis) {
        return String.format(Locale.ROOT, "[%02d:%02d.%03d]", millis / 60_000, millis % 60_000 / 1_000, millis % 1_000);
    }

    private static List<Long> timestamps(String line) {
        Matcher matcher = TIME.matcher(line == null ? "" : line);
        List<Long> times = new ArrayList<>();
        while (matcher.find()) {
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
            times.add(minutes * 60_000L + seconds * 1_000L + millis);
        }
        return times;
    }
}
