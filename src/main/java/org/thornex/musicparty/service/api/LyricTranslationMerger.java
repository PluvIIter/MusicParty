package org.thornex.musicparty.service.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Preserve the plain-LRC API, emitting translation at the matched original timestamp. */
final class LyricTranslationMerger {
    private static final Pattern TIMESTAMP = Pattern.compile("\\[(\\d{1,3}):([0-5]?\\d)(?:[.:](\\d{1,3}))?\\]");
    private static final long MAX_DRIFT_MS = 300;
    private record Line(long time, String text) {}

    static String merge(String original, String translation) {
        if (original == null || original.isBlank()) return original == null ? "" : original;
        if (translation == null || translation.isBlank()) return original;
        List<Line> originals = parse(original);
        List<Line> translations = parse(translation);
        if (originals.isEmpty() || translations.isEmpty()) return original;
        StringBuilder result = new StringBuilder(original);
        if (!original.endsWith("\n")) result.append('\n');
        boolean[] used = new boolean[originals.size()];
        for (Line translated : translations) {
            int best = -1;
            long distance = MAX_DRIFT_MS + 1;
            for (int i = 0; i < originals.size(); i++) {
                Line source = originals.get(i);
                long diff = Math.abs(source.time - translated.time);
                if (!used[i] && diff < distance) { best = i; distance = diff; }
            }
            if (best < 0 || distance > MAX_DRIFT_MS) continue;
            Line source = originals.get(best);
            used[best] = true;
            if (source.text.equals(translated.text)) continue;
            long ms = source.time;
            result.append(String.format(java.util.Locale.ROOT, "[%02d:%02d.%03d]", ms / 60000, (ms / 1000) % 60, ms % 1000));
            result.append(translated.text).append('\n');
        }
        return result.toString();
    }

    private static List<Line> parse(String lrc) {
        List<Line> lines = new ArrayList<>();
        for (String raw : lrc.split("\\R")) {
            Matcher matcher = TIMESTAMP.matcher(raw);
            List<Long> stamps = new ArrayList<>();
            while (matcher.find()) {
                String fraction = matcher.group(3);
                long millis = fraction == null ? 0 : Integer.parseInt((fraction + "000").substring(0, 3));
                stamps.add(Long.parseLong(matcher.group(1)) * 60000 + Long.parseLong(matcher.group(2)) * 1000 + millis);
            }
            String text = TIMESTAMP.matcher(raw).replaceAll("").trim();
            if (!text.isEmpty()) for (long stamp : stamps) lines.add(new Line(stamp, text));
        }
        lines.sort(Comparator.comparingLong(Line::time));
        return lines;
    }
    private LyricTranslationMerger() {}
}
