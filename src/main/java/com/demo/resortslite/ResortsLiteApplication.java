package com.demo.resortslite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * ResortsLite Spring Boot application entry point.
 *
 * <p>@EnableCaching activates Spring Cache abstraction backed by Amazon ElastiCache
 * for Redis, replacing the previous in-memory caching without TTL (blocker cr-java-0067).
 * Spring Session with Redis is configured in application.properties and
 * BookingController to replace HTTP session state storage (blocker cr-java-0065).</p>
 */
@SpringBootApplication
@EnableCaching
public class ResortsLiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResortsLiteApplication.class, args);
    }
}
