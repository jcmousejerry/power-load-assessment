package com.loadflex.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.loadflex")
public class LoadflexConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoadflexConsumerApplication.class, args);
    }
}
