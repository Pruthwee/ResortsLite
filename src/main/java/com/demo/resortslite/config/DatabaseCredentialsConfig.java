package com.demo.resortslite.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import javax.annotation.PostConstruct;
import java.util.logging.Logger;

/**
 * Configuration class to retrieve database credentials from AWS Secrets Manager.
 * This replaces hard-coded credentials in the source code, enabling secure credential
 * management with automatic rotation support.
 */
@Configuration
public class DatabaseCredentialsConfig {

    private static final Logger logger = Logger.getLogger(DatabaseCredentialsConfig.class.getName());

    @Value("${aws.secretsmanager.secret.name:resorts-db-credentials}")
    private String secretName;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    private String dbHost;
    private String dbUser;
    private String dbPassword;

    @PostConstruct
    public void init() {
        try {
            retrieveCredentialsFromSecretsManager();
        } catch (Exception e) {
            logger.warning("Failed to retrieve credentials from AWS Secrets Manager: " + e.getMessage());
            // Fallback to environment variables for local development
            loadFromEnvironmentVariables();
        }
    }

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     * Expected secret format (JSON):
     * {
     *   "host": "db-prod.resorts-internal.com",
     *   "username": "admin",
     *   "password": "secure-password"
     * }
     */
    private void retrieveCredentialsFromSecretsManager() {
        try (SecretsManagerClient client = SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();

            GetSecretValueResponse response = client.getSecretValue(request);
            String secretString = response.secretString();

            // Parse JSON secret
            ObjectMapper mapper = new ObjectMapper();
            JsonNode secretJson = mapper.readTree(secretString);

            this.dbHost = secretJson.get("host").asText();
            this.dbUser = secretJson.get("username").asText();
            this.dbPassword = secretJson.get("password").asText();

            logger.info("Successfully retrieved database credentials from AWS Secrets Manager");
        } catch (Exception e) {
            logger.severe("Error retrieving secret from AWS Secrets Manager: " + e.getMessage());
            throw new RuntimeException("Failed to retrieve database credentials", e);
        }
    }

    /**
     * Fallback method to load credentials from environment variables for local development.
     */
    private void loadFromEnvironmentVariables() {
        this.dbHost = System.getenv().getOrDefault("DB_HOST", "localhost");
        this.dbUser = System.getenv().getOrDefault("DB_USER", "sa");
        this.dbPassword = System.getenv().getOrDefault("DB_PASSWORD", "");
        logger.info("Loaded database credentials from environment variables (fallback mode)");
    }

    public String getDbHost() {
        return dbHost;
    }

    public String getDbUser() {
        return dbUser;
    }

    public String getDbPassword() {
        return dbPassword;
    }
}
