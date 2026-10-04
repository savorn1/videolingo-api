package com.example.videolingo.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class S3Config {

    @Value("${aws.access-key-id}")
    private String accessKeyId;

    @Value("${aws.secret-access-key}")
    private String secretAccessKey;

    @Value("${aws.region}")
    private String region;

    @Value("${s3.endpoint:}")
    private String endpoint;

    @Bean
    S3Client s3Client() {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                // MinIO (and most S3-compatible stores) need path-style bucket
                // addressing — virtual-hosted-style (bucket.host) only resolves
                // against real AWS S3's public DNS.
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build());
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    // Signs direct browser→bucket uploads (see VideoIngestService.presignUpload).
    // Signs against the public endpoint, since that's the host the browser uses.
    @Bean
    S3Presigner s3Presigner(@Value("${s3.public-endpoint:}") String publicEndpoint) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build());
        String target = publicEndpoint != null && !publicEndpoint.isBlank() ? publicEndpoint : endpoint;
        if (target != null && !target.isBlank()) {
            builder.endpointOverride(URI.create(target));
        }
        return builder.build();
    }
}
