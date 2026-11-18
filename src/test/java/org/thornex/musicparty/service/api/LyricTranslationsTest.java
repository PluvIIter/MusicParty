package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LyricTranslationsTest {
    @Test
    void translationBecomesItsOwnLineRightAfterTheOriginal() {
        assertEquals("[00:01.00]Hello\n[00:01.000]你好\n[00:02.50]World\n[00:02.500]世界",
                LyricTranslations.merge("[00:01.00]Hello\n[00:02.50]World", "[00:01.00]你好\n[00:02.50]世界"));
    }

    @Test
    void headerLinesAndUntimedLinesArePreserved() {
        String lyric = "[ti:Song]\n[ar:Artist]\n[00:01.00]Hello\n";
        assertEquals("[ti:Song]\n[ar:Artist]\n[00:01.00]Hello\n[00:01.000]你好",
                LyricTranslations.merge(lyric, "[00:01.00]你好"));
    }

    @Test
    void smallTimestampDriftStillMatches() {
        assertEquals("[00:01.00]Hello\n[00:01.000]你好",
                LyricTranslations.merge("[00:01.00]Hello", "[00:01.12]你好"));
    }

    @Test
    void translationBeyondTheDriftWindowIsIgnored() {
        String lyric = "[00:01.00]Hello\n[00:02.00]World";
        assertEquals(lyric, LyricTranslations.merge(lyric, "[00:01.50]无关"));
    }

    @Test
    void eachTranslationLineIsUsedOnlyOnce() {
        String merged = LyricTranslations.merge("[00:01.00]Hello\n[00:01.00]Hello again", "[00:01.00]你好");
        assertEquals("[00:01.00]Hello\n[00:01.000]你好\n[00:01.00]Hello again", merged);
    }

    @Test
    void repeatedSourceTimestampsKeepTheirIndependentTranslationMatches() {
        String merged = LyricTranslations.merge(
                "[00:01.00][00:02.00]Hello",
                "[00:01.00]Hello\n[00:02.00]你好");
        assertEquals("[00:01.00][00:02.00]Hello\n[00:02.000]你好", merged);
    }

    @Test
    void laterRepeatedTimestampDoesNotDelayIntermediateLyrics() {
        assertEquals("[00:01.00][00:20.00]Hello\n[00:01.000]你好\n[00:02.00]World\n[00:02.000]世界\n[00:20.000]你好",
                LyricTranslations.merge("[00:01.00][00:20.00]Hello\n[00:02.00]World",
                        "[00:01.00][00:20.00]你好\n[00:02.00]世界"));
    }

    @Test
    void identicalTextIsNotDuplicated() {
        String lyric = "[00:01.00]Hello";
        assertEquals(lyric, LyricTranslations.merge(lyric, "[00:01.00]Hello"));
    }

    @Test
    void missingTranslationReturnsTheOriginal() {
        assertEquals("[00:01.00]Hello", LyricTranslations.merge("[00:01.00]Hello", ""));
        assertEquals("[00:01.00]Hello", LyricTranslations.merge("[00:01.00]Hello", null));
        assertEquals("", LyricTranslations.merge(null, "[00:01.00]你好"));
    }
}
