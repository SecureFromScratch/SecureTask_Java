package com.securetask;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests.
 *
 * The container is started once in the static initializer and stays up for the
 * entire test run. Using @Testcontainers + @Container on an inherited static field
 * causes the container to be stopped after each test class, which breaks the
 * Spring context cache — subsequent classes reuse the cached context but the
 * DataSource is pointing at a stopped container. Starting manually avoids this.
 *
 * Testcontainers' Ryuk sidecar handles cleanup on JVM exit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class BaseIntegrationTest {

    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("securetask_test")
                    .withUsername("test")
                    .withPassword("test");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("test.datasource.url", postgres::getJdbcUrl);
        registry.add("test.datasource.username", postgres::getUsername);
        registry.add("test.datasource.password", postgres::getPassword);
    }
}
