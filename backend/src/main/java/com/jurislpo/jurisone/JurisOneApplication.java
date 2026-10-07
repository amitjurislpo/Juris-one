package com.jurislpo.jurisone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** JurisOne REST API. The Next.js app (port 3005) is its only client; it proxies /api/* here. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class JurisOneApplication {
    public static void main(String[] args) {
        SpringApplication.run(JurisOneApplication.class, args);
    }
}
