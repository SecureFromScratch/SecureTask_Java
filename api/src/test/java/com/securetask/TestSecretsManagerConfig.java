package com.securetask;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import javax.sql.DataSource;

/**
 * Replaces the production SecretsManagerConfig during tests.
 * Builds the DataSource directly from Testcontainers properties,
 * so tests do not require a running LocalStack instance.
 */
@TestConfiguration
public class TestSecretsManagerConfig {

    @Value("${test.datasource.url}")
    private String url;

    @Value("${test.datasource.username}")
    private String username;

    @Value("${test.datasource.password}")
    private String password;

    /**
     * Provides a no-op SecretsManagerClient so no AWS calls are made during tests.
     */
    @Bean
    @Primary
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                .credentialsProvider(
                    software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("test", "test")
                    )
                )
                .endpointOverride(java.net.URI.create("http://localhost:9999")) // unreachable — never called
                .build();
    }

    @Bean("jwtSecret")
    @Primary
    public String jwtSecret() {
        // "securetask-test-jwt-secret-key!!" = 32 bytes, Base64-encoded
        return "c2VjdXJldGFzay10ZXN0LWp3dC1zZWNyZXQta2V5ISE=";
    }

    @Bean
    @Primary
    public DataSource dataSource() {
        DataSourceProperties props = new DataSourceProperties();
        props.setUrl(url);
        props.setUsername(username);
        props.setPassword(password);
        try {
            props.afterPropertiesSet();
        } catch (Exception e) {
            throw new RuntimeException("Failed to configure DataSource", e);
        }
        return props.initializeDataSourceBuilder().build();
    }
}
