package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Integration / smoke test for the Spring Boot application context.
 * Verifies that the application context loads successfully.
 */
@SpringBootTest
@ActiveProfiles("test")
class ResortsLiteApplicationTest {

    /**
     * Verifies that the Spring application context loads without errors.
     * This is the standard Spring Boot smoke test.
     */
    @Test
    void contextLoads() {
        // If the context fails to load, this test will fail automatically.
        // No explicit assertion needed — Spring Boot test infrastructure handles it.
    }

    /**
     * Verifies that the main() entry point can be invoked without throwing.
     */
    @Test
    void main_doesNotThrow() {
        assertDoesNotThrow(() -> ResortsLiteApplication.main(new String[]{}),
                "main() should not throw an exception");
    }
}
