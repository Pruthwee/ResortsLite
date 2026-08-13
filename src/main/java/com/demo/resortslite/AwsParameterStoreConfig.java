package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

/**
 * Configuration class for AWS Systems Manager Parameter Store integration.
 * Provides centralized access to externalized configuration parameters stored in AWS SSM.
 * 
 * This replaces hard-coded environment-specific URLs with cloud-native configuration management,
 * enabling environment-agnostic deployments across dev, staging, and production.
 */
@Configuration
public class AwsParameterStoreConfig {

    private final SsmClient ssmClient;

    public AwsParameterStoreConfig() {
        // Initialize AWS Systems Manager client with default credentials and region
        // Credentials are automatically retrieved from EC2 instance profile, ECS task role,
        // or environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
        String region = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        this.ssmClient = SsmClient.builder()
                .region(Region.of(region))
                .build();
    }

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     * 
     * @param parameterName The name of the parameter in Parameter Store (e.g., /resortslite/inventory-service-url)
     * @param defaultValue Fallback value if parameter is not found or SSM is unavailable
     * @return The parameter value from SSM or the default value
     */
    public String getParameter(String parameterName, String defaultValue) {
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true) // Decrypt SecureString parameters
                    .build();

            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (Exception e) {
            // Log warning and return default value if parameter retrieval fails
            System.err.println("Warning: Failed to retrieve parameter '" + parameterName + 
                             "' from AWS Systems Manager. Using default value. Error: " + e.getMessage());
            return defaultValue;
        }
    }

    @Bean
    public SsmClient ssmClient() {
        return this.ssmClient;
    }
}
