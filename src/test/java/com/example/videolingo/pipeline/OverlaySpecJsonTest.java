package com.example.videolingo.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OverlaySpecJsonTest {

    // The spec goes through JSON twice (request body, then the job's parameters): nothing may be lost.
    @Test
    void survivesAJsonRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        OverlayRules.Spec spec = new OverlayRules.Spec(List.of(OverlayRulesTest.text("Hello", "FADE", 0.8), OverlayRulesTest.image("overlay-uploads/a.png")));
        OverlayRules.Spec back = mapper.readValue(mapper.writeValueAsString(spec), OverlayRules.Spec.class);
        assertEquals(spec, back);
        assertEquals("Hello", back.layers().get(0).text());
    }

    @Test
    void audioSpecSurvivesToo() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AudioEditRules.Spec spec = new AudioEditRules.Spec("audio-uploads/a.mp3", List.of(new AudioEditRules.Clip(0, 1000, 500, 0.5)),
                List.of(new AudioEditRules.Range(1, 2)), 1.5, 100, 200, true, "LIGHT", true, 1.25, -2, 0.5, "MONO",
                new AudioEditRules.Music("audio-uploads/m.mp3", 0.3, true, true, 0));
        assertEquals(spec, mapper.readValue(mapper.writeValueAsString(spec), AudioEditRules.Spec.class));
    }
}
