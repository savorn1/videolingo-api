package com.example.videolingo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
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
}
