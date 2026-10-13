package com.example.videolingo.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

// Reading the speech-to-text answer (verbose_json with segment and word timestamps).
class WhisperParseTest {

    @Test
    void segmentsComeWithTheirWords() throws Exception {
        String json = """
                {"language":"english","segments":[{"start":0.0,"end":2.0,"text":" Hello, um, welcome."},
                 {"start":2.5,"end":4.0,"text":" Today: greetings."}],
                 "words":[{"word":"Hello","start":0.0,"end":0.5},{"word":"um","start":0.7,"end":0.9},
                 {"word":"welcome","start":1.1,"end":1.8},{"word":"Today","start":2.5,"end":2.9},
                 {"word":"greetings","start":3.0,"end":3.8}]}
                """;
        WhisperClient.Result r = WhisperClient.parse(new ObjectMapper().readTree(json));
        assertEquals("english", r.detectedLanguage());
        assertEquals(2, r.segments().size());
        assertEquals("Hello, um, welcome.", r.segments().get(0).text());
        assertEquals(3, r.segments().get(0).words().size());
        assertEquals(700, r.segments().get(0).words().get(1).startMs());
        assertEquals("um", r.segments().get(0).words().get(1).text());
        assertEquals(2, r.segments().get(1).words().size());
    }

    @Test
    void withoutWordsTheSegmentsStillRead() throws Exception {
        String json = "{\"segments\":[{\"start\":0,\"end\":1,\"text\":\"Hi\"}]}";
        WhisperClient.Result r = WhisperClient.parse(new ObjectMapper().readTree(json));
        assertEquals(1, r.segments().size());
        assertNull(r.segments().get(0).words());
        assertNull(r.detectedLanguage());
    }
}
