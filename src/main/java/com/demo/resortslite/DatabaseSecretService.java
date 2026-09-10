package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

/**
 * REMEDIATION cr-java-0069: DatabaseSecretService retrieves database credentials
 * from AWS Secrets Manager at runtime, eliminating hard-coded credentials in source code.
 * This enables automatic credential rotation through AWS Secrets Manager without
 * requiring application redeployment.
 */
@Service
public class DatabaseSecretService {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseSecretService.class);

    @Value("${app.db.secret.name:resorts-lite/db-credentials}")
    private String secretName;

    @Value("${app.aws.region:us-east-1}")
    private String awsRegion;

    private SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        secretsManagerClient = SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     * The secret is expected to be a JSON object with fields: host, username, password.
     *
     * @return Map containing host, username, and password
     */
    public Map<String, String> getDatabaseCredentials() {
        Map<String, String> credentials = new HashMap<>();
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();

            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            String secretString = response.secretString();

            JsonNode secretJson = objectMapper.readTree(secretString);
            credentials.put("host", secretJson.get("host").asText());
            credentials.put("username", secretJson.get("username").asText());
            credentials.put("password", secretJson.get("password").asText());

            logger.info("Successfully retrieved database credentials from AWS Secrets Manager for secret: {}", secretName);
        } catch (SecretsManagerException e) {
            logger.error("Failed to retrieve database credentials from AWS Secrets Manager: {}", e.getMessage());
            throw new RuntimeException("Unable to retrieve database credentials from Secrets Manager", e);
        } catch (Exception e) {
            logger.error("Error parsing database credentials from AWS Secrets Manager: {}", e.getMessage());
            throw new RuntimeException("Unable to parse database credentials from Secrets Manager", e);
        }
        return credentials;
    }
}
