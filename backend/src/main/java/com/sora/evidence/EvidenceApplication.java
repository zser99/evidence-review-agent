package com.sora.evidence;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableCaching
@EnableJpaRepositories(considerNestedRepositories = true)
@EnableAsync
@EnableScheduling
public class EvidenceApplication {
    public static void main(String[] args) {
        SpringApplication.run(EvidenceApplication.class, args);
    }
}
