package com.example.videolingo.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    // Only created when a key is configured; AiClientService checks for it and
    // answers 503 otherwise, so the app starts fine without AI.
    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("!'${ai.api-key:}'.isBlank()")
    AnthropicClient anthropicClient(AiProperties props) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(props.apiKey())
                // Quiz/summary calls on long transcripts can take a few minutes.
                .timeout(Duration.ofMinutes(10))
                .maxRetries(2);
        if (props.baseUrl() != null && !props.baseUrl().isBlank()) {
            builder.baseUrl(props.baseUrl());
        }
        return builder.build();
    }
}
