package com.sora.evidence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(String jwtSecret, long jwtTtlMinutes, String seedDir, boolean seedDemoUsers,
                            String demoPassword, Ai ai, Storage storage, University university) {

    public record Ai(String baseUrl, String internalToken, int timeoutSeconds) {
    }

    public record Storage(String type, String bucket, String endpoint, String region, String accessKey,
                          String secretKey, boolean pathStyle, String localDir) {
    }

    public record University(String apiUrl, String apiKey) {
    }
}
