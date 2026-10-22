package com.example.videolingo.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.example.videolingo.pipeline.VideoEditService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

// The trim body the editor sent, whose `look` leaves out warmth and sharpen, is read (it was a 400 before).
class JacksonConfigTest {

    @Test
    void aLookWithoutWarmthOrSharpenIsRead() {
        JsonMapper.Builder builder = JsonMapper.builder();
        JacksonConfig.allowMissingPrimitives(builder);
        String json = """
                {"startMs":0,"endMs":184000,"crop":null,"scale":null,"audio":null,
                 "look":{"brightness":0,"contrast":1.15,"saturation":1.4,"blur":0,"grayscale":false,"sepia":false,"vignette":false},
                 "stabilize":true,"greenScreen":{"keyColor":"#27282b","similarity":0.3,"background":"#0b4bf9"}}
                """;
        VideoEditService.TrimRequest r = builder.build().readValue(json, VideoEditService.TrimRequest.class);
        assertEquals(1.15, r.look().contrast(), 1e-9);
        assertEquals(0, r.look().warmth(), 1e-9);
        assertFalse(r.look().sharpen());
        assertEquals("#0b4bf9", r.greenScreen().background());
    }
}
