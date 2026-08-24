package com.loadflex.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.loadflex")
@EnableScheduling
public class LoadflexServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(LoadflexServerApplication.class, args);
    }
}
