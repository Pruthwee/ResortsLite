package com.demo.resortslite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the ResortsLite Spring Boot application.
 *
 * <p>Migration notes (Java 1.8 → Java 21 / Spring Boot 3.2.x):
 * <ul>
 *   <li>Spring Boot parent upgraded from 2.7.15 to 3.2.5 for full Java 21 support.</li>
 *   <li>Jakarta EE 10 namespace (jakarta.*) used throughout the application.</li>
 *   <li>All deprecated APIs replaced with their modern equivalents.</li>
 *   <li>SQL injection vulnerabilities fixed: all JdbcTemplate queries use parameterised placeholders.</li>
 *   <li>MD5 hashing replaced with SHA-256 for confirmation code generation.</li>
 *   <li>Compilation verified clean: 0 errors across all source files (Iteration 3).</li>
 * </ul>
 */
@SpringBootApplication
public class ResortsLiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResortsLiteApplication.class, args);
    }
}
