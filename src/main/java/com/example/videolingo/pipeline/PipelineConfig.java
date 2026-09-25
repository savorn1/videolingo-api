package com.example.videolingo.pipeline;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PipelineProperties.class)
public class PipelineConfig {
}
