package com.loadflex.consumer.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestClientConfig {

    @Bean
    public RestTemplate restTemplate(
            RestTemplateBuilder builder,
            @Value("${loadflex.algorithm.connect-timeout-seconds}") long connectTimeoutSeconds,
            @Value("${loadflex.algorithm.read-timeout-seconds}") long readTimeoutSeconds) {
        return builder.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .setReadTimeout(Duration.ofSeconds(readTimeoutSeconds))
                .build();
    }
}
