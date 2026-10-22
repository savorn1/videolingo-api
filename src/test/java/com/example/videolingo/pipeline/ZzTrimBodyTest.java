package com.example.videolingo.pipeline;

import org.junit.jupiter.api.Test;

class ZzTrimBodyTest {
    @Test
    void read() {
        String json =
                "{\"startMs\":0,\"endMs\":184000,\"crop\":null,\"scale\":null,\"audio\":null,\"look\":{\"brightness\":0,\"contrast\":1.15,\"saturation\":1.4,\"blur\":0,\"grayscale\":false,\"sepia\":false,\"vignette\":false},\"stabilize\":true,\"greenScreen\":{\"keyColor\":\"#27282b\",\"similarity\":0.3,\"background\":\"#0b4bf9\"}}";
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        try {
            mapper.readValue(json, VideoEditService.TrimRequest.class);
            System.out.println("RESULT: read fine");
        } catch (Exception e) {
            System.out.println("RESULT: " + e.getClass().getSimpleName() + ": "
                    + e.getMessage().split("\n")[0]);
        }
    }
}
