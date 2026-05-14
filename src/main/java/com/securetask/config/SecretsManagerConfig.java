package com.securetask.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import javax.sql.DataSource;
import java.net.URI;

/**
 * Loads database credentials from AWS Secrets Manager (or LocalStack) at startup.
 *
 * Why not application.properties?
 * Hard-coding database passwords in property files risks leaking them via version
 * control, log aggregators, or environment variable dumps. Secrets Manager keeps
 * the credential outside the application artifact entirely.
 */
@Configuration
public class SecretsManagerConfig {

    @Value("${aws.region}")
    private String awsRegion;

    @Value("${aws.secretsmanager.endpoint:}")
    private String secretsManagerEndpoint;

    @Value("${aws.secretsmanager.secretName}")
    private String secretName;

    @Bean
    @ConditionalOnMissingBean
    public SecretsManagerClient secretsManagerClient() {
        var builder = SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                // Use environment-variable credentials (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY).
                // For LocalStack, any non-empty value works.
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                            System.getenv().getOrDefault("AWS_ACCESS_KEY_ID", "test"),
                            System.getenv().getOrDefault("AWS_SECRET_ACCESS_KEY", "test")
                        )
                    )
                );

        // Override the endpoint when running against LocalStack.
        if (secretsManagerEndpoint != null && !secretsManagerEndpoint.isBlank()) {
            builder.endpointOverride(URI.create(secretsManagerEndpoint));
        }

        return builder.build();
    }

    /**
     * Reads the DB secret from Secrets Manager and builds a DataSource.
     * The app will fail to start if the secret is missing — no silent fallback.
     */
    @Bean
    @ConditionalOnMissingBean
    public DataSource dataSource(SecretsManagerClient client) throws Exception {
        GetSecretValueRequest req = GetSecretValueRequest.builder()
                .secretId(secretName)
                .build();

        GetSecretValueResponse resp = client.getSecretValue(req);
        String secretJson = resp.secretString();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode secret = mapper.readTree(secretJson);

        String url = secret.get("url").asText();
        String username = secret.get("username").asText();
        String password = secret.get("password").asText();

        DataSourceProperties props = new DataSourceProperties();
        props.setUrl(url);
        props.setUsername(username);
        props.setPassword(password);
        props.afterPropertiesSet();

        return props.initializeDataSourceBuilder().build();
    }
}
