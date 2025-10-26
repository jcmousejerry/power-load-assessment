package com.chen.consumer;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.chen.consumer.mapper")
public class PowerLoadAssessmentConsumerApplication {
    public static void main(String[] args) {
        SpringApplication.run(PowerLoadAssessmentConsumerApplication.class, args);
    }
}
