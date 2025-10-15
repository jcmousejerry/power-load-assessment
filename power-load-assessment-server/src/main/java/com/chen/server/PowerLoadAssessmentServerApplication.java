package com.chen.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.chen.server.mapper")
@SpringBootApplication
public class PowerLoadAssessmentServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PowerLoadAssessmentServerApplication.class, args);
    }

}
