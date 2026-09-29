package com.example.videolingo.pipeline;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

@Configuration
@EnableConfigurationProperties(PipelineProperties.class)
// Backs @Retryable on OpenAiHttpClient (proxies the bean via Spring AOP).
@EnableRetry
public class PipelineConfig {
}
