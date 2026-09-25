package com.example.videolingo.subtitle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubtitleLogicTest {

    private static final SubtitleRules RULES = SubtitleRules.DEFAULT;

    // ── segmenter ────────────────────────────────────────────────────────

    @Test
    void shortSegmentBecomesOneSingleLineCue() {
        List<Cue> cues = SubtitleSegmenter.segment(List.of(new Cue(0, 2000, "  Hello   there! ")), RULES);
        assertEquals(List.of(new Cue(0, 2000, "Hello there!")), cues);
    }

    @Test
    void mediumSegmentIsWrappedIntoTwoBalancedLines() {
        Cue cue = SubtitleSegmenter.segment(List.of(new Cue(0, 4000, "I would like a large coffee with oat milk, please.")), RULES).get(0);
        String[] lines = cue.text().split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].length() <= 42 && lines[1].length() <= 42);
        assertTrue(Math.abs(lines[0].length() - lines[1].length()) <= 12, cue.text());
    }

    @Test
    void longSegmentIsSplitAtPunctuationAndTimeIsSharedByLength() {
        String text = "Welcome to the café, we have hot drinks and cold drinks today. "
                + "Would you like to try our seasonal pumpkin latte with extra cinnamon on top?";
        List<Cue> cues = SubtitleSegmenter.segment(List.of(new Cue(10_000, 20_000, text)), RULES);
        assertTrue(cues.size() >= 2);
        assertTrue(cues.get(0).text().replace("\n", " ").endsWith("today."), cues.get(0).text());
        assertEquals(10_000, cues.get(0).startMs());
        assertEquals(20_000, cues.get(cues.size() - 1).endMs());
        for (int i = 1; i < cues.size(); i++) {
            assertEquals(cues.get(i - 1).endMs(), cues.get(i).startMs(), "cues are contiguous");
        }
        for (Cue c : cues) {
            assertTrue(SubtitleText.length(c.text().replace("\n", " ")) <= RULES.maxCharsPerCue() + 1, c.text());
        }
    }

    @Test
    void spacelessScriptsAreSplitBetweenCharacters() {
        String ja = "本日はご来店いただき誠にありがとうございます。季節限定のかぼちゃラテはいかがでしょうか。温かいお飲み物と冷たいお飲み物がございます。";
        SubtitleRules ja16 = SubtitleRules.defaultsFor("ja");
        assertEquals(16, ja16.maxCharsPerLine());
        List<Cue> cues = SubtitleSegmenter.segment(List.of(new Cue(0, 9000, ja)), ja16);
        assertTrue(cues.size() >= 2, "66 characters don't fit one 2×16 cue");
        assertEquals(ja, String.join("", cues.stream().map(c -> c.text().replace("\n", "")).toList()), "no characters lost or spaces added");
        for (Cue c : cues) {
            for (String line : c.text().split("\n")) {
                assertTrue(SubtitleText.length(line) <= 16, line);
                assertFalse(line.startsWith("。"), "never start a line with closing punctuation: " + line);
            }
        }
    }

    @Test
    void shortCuesAreStretchedButNeverIntoTheNextCue() {
        List<Cue> cues = SubtitleSegmenter.segment(List.of(new Cue(0, 300, "Hi."), new Cue(600, 900, "Yes."), new Cue(5000, 5200, "Bye.")), RULES);
        assertEquals(600, cues.get(0).endMs(), "stretched up to the next cue only");
        assertEquals(1600, cues.get(1).endMs(), "stretched to the minimum");
        assertEquals(6000, cues.get(2).endMs(), "last cue stretched to the minimum");
    }

    @Test
    void cuesEndAtSentenceBreaksEvenWhenThatLeavesThemShort() {
        String ja = "ホットですか、アイスですか？本日は季節限定のかぼちゃラテもございます。いかがでしょうか。";
        List<Cue> cues = SubtitleSegmenter.segment(List.of(new Cue(0, 8000, ja)), SubtitleRules.defaultsFor("ja"));
        assertEquals("ホットですか、アイスですか？", cues.get(0).text().replace("\n", ""));
        for (Cue c : cues) {
            assertFalse(c.text().replace("\n", "").startsWith("ます"), "never split ござい|ます: " + c.text());
        }
    }

    @Test
    void languageDefaultsPickCjkLimitsOnlyForChineseAndJapanese() {
        assertEquals(SubtitleRules.CJK, SubtitleRules.defaultsFor("zh-Hant"));
        assertEquals(SubtitleRules.DEFAULT, SubtitleRules.defaultsFor("km"));
        assertEquals(SubtitleRules.DEFAULT, SubtitleRules.defaultsFor(null));
    }

    @Test
    void emptyAndBrokenSegmentsAreDropped() {
        assertTrue(SubtitleSegmenter.segment(List.of(new Cue(0, 1000, "   "), new Cue(2000, 1000, "backwards")), RULES).isEmpty());
    }

    // ── quality ──────────────────────────────────────────────────────────

    @Test
    void qualityFlagsEachKindOfProblem() {
        List<Cue> cues = List.of(
                new Cue(0, 500, "Too short"),
                new Cue(400, 2000, "Overlapped by the one before"),
                new Cue(3000, 4000, "This line is definitely far too long to fit on a subtitle line"),
                new Cue(5000, 6000, "a\nb\nc"),
                new Cue(7000, 16000, "Too long on screen"),
                new Cue(17000, 17500, "Way too many characters for half a second"));
        List<SubtitleIssue.Type> types = SubtitleQuality.check(cues, RULES).stream().map(SubtitleIssue::type).toList();
        assertTrue(types.contains(SubtitleIssue.Type.TOO_SHORT));
        assertTrue(types.contains(SubtitleIssue.Type.OVERLAP));
        assertTrue(types.contains(SubtitleIssue.Type.LINE_TOO_LONG));
        assertTrue(types.contains(SubtitleIssue.Type.TOO_MANY_LINES));
        assertTrue(types.contains(SubtitleIssue.Type.TOO_LONG));
        assertTrue(types.contains(SubtitleIssue.Type.TOO_FAST));
    }

    @Test
    void cleanTrackHasNoIssues() {
        assertTrue(SubtitleQuality.check(List.of(new Cue(0, 2000, "Hello."), new Cue(2000, 4000, "How are you?")), RULES).isEmpty());
    }

    // ── files ────────────────────────────────────────────────────────────

    @Test
    void parsesSrtKeepingLineBreaksAndSkippingBrokenCues() {
        String srt = "﻿1\r\n00:00:01,000 --> 00:00:02,500\r\n<i>Hello</i>\r\nthere\r\n\r\n2\r\n00:00:05,000 --> 00:00:04,000\r\nBackwards\r\n\r\n3\r\n00:00:06,000 --> 00:00:07,000\r\nEnd";
        SubtitleFiles.Parsed parsed = SubtitleFiles.parse(srt);
        assertEquals("srt", parsed.format());
        assertEquals(List.of(new Cue(1000, 2500, "Hello\nthere"), new Cue(6000, 7000, "End")), parsed.cues());
        assertEquals(1, parsed.warnings().size());
    }

    @Test
    void parsesVttWithHeaderNotesSettingsAndShortTimestamps() {
        String vtt = "WEBVTT\n\nNOTE hi\n\nid-1\n00:01.000 --> 00:02.000 align:start\n<v Ana>Hi &amp; bye\n\n01:00:00.000 --> 01:00:01.5\nLate";
        SubtitleFiles.Parsed parsed = SubtitleFiles.parse(vtt);
        assertEquals("vtt", parsed.format());
        assertEquals(List.of(new Cue(1000, 2000, "Hi & bye"), new Cue(3_600_000, 3_601_500, "Late")), parsed.cues());
    }

    @Test
    void rejectsFilesWithoutTimings() {
        assertThrows(IllegalArgumentException.class, () -> SubtitleFiles.parse("just some text"));
    }

    @Test
    void writesSrtAndEscapedVtt() {
        List<Cue> cues = List.of(new Cue(0, 1500, "A & B\n<tag> --> x"));
        assertEquals("1\n00:00:00,000 --> 00:00:01,500\nA & B\n<tag> --> x\n\n", SubtitleFiles.toSrt(cues));
        assertEquals("WEBVTT\n\n00:00:00.000 --> 00:00:01.500\nA &amp; B\n&lt;tag> --&gt; x\n\n", SubtitleFiles.toVtt(cues));
    }

    @Test
    void writtenFilesParseBackToTheSameCues() {
        List<Cue> cues = List.of(new Cue(0, 1500, "One\ntwo"), new Cue(3_723_456, 3_725_000, "Three"));
        assertEquals(cues, SubtitleFiles.parse(SubtitleFiles.toSrt(cues)).cues());
        assertEquals(cues, SubtitleFiles.parse(SubtitleFiles.toVtt(cues)).cues());
    }
}
