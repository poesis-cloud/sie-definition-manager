package com.sif.sie.definitionmanager;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Smoke test: validates context startup + Flyway migration against real
 * PostgreSQL (Testcontainers). H2 is incompatible with TABLE_PER_CLASS +
 * NAMED_ENUM.
 */
@SpringBootTest
@ActiveProfiles("tc")
@Testcontainers
class DefinitionManagerApplicationTests {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16.3-alpine");

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
    }

    @Test
    void contextLoads() {
        // Context startup validates Flyway migration + Hibernate schema validation.
    }
}
