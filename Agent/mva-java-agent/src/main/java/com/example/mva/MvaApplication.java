package com.example.mva;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the Minimal Viable Agent application.
 * <p>
 * A hand-written ReAct (Reasoning & Acting) agent built from scratch
 * with Java 21 and Spring Boot 3.x — no AI framework dependencies.
 */
@SpringBootApplication
@EnableConfigurationProperties
public class MvaApplication {

    public static void main(String[] args) {
        SpringApplication.run(MvaApplication.class, args);
        System.out.println("""

                ╔══════════════════════════════════════════════╗
                ║   MVA — Minimal Viable Agent                ║
                ║   Running on http://localhost:8080           ║
                ║   Open chat UI: http://localhost:8080/       ║
                ╚══════════════════════════════════════════════╝
                """);
    }
}
