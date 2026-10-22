package com.example.videolingo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Spring Boot 4's Jackson auto-configuration defaults to Jackson 3
// (tools.jackson.databind.ObjectMapper). Code that
// still uses the Jackson 2 API (com.fasterxml.jackson.databind), which is
// only present transitively (jjwt-jackson, springdoc) and gets no
// auto-configured bean, so it's provided explicitly here.
@Configuration
public class JacksonConfig {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    // Request bodies are read by Jackson 3, which by default refuses a JSON object that leaves out a number or
    // true/false field of a record (FAIL_ON_NULL_FOR_PRIMITIVES): an older or simpler client sending a `look` without
    // `warmth` got 400 "malformed". Left out, such a field now takes its default (0 / false), as with Jackson 2.
    @Bean
    JsonMapperBuilderCustomizer lenientPrimitives() {
        return JacksonConfig::allowMissingPrimitives;
    }

    static void allowMissingPrimitives(tools.jackson.databind.json.JsonMapper.Builder builder) {
        builder.disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }
}
