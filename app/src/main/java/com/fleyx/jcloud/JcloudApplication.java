package com.fleyx.jcloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用启动入口。
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class JcloudApplication {

    public static void main(String[] args) {
        SpringApplication.run(JcloudApplication.class, args);
    }
}
