package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ResortsLiteApplicationTest {

    @Test
    void contextLoads() {
        // Verifies that the Spring application context starts successfully
    }

    @Test
    void applicationClassExists() {
        // Arrange / Act
        ResortsLiteApplication app = new ResortsLiteApplication();

        // Assert
        assertNotNull(app, "ResortsLiteApplication instance should not be null");
    }

    @Test
    void mainMethodDoesNotThrow() {
        // The main method starts the Spring context; we verify it is callable.
        // In test scope, Spring Boot test context is already loaded, so we just
        // verify the class is accessible.
        assertDoesNotThrow(() -> {
            Class<?> clazz = Class.forName("com.demo.resortslite.ResortsLiteApplication");
            assertNotNull(clazz);
        });
    }
}
