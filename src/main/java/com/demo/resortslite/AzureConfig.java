package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Azure services configuration
 * Configures Azure Key Vault, Blob Storage, and other Azure services
 */
@Configuration
public class AzureConfig {

    @Value("${azure.keyvault.uri:}")
    private String keyVaultUri;

    @Value("${azure.keyvault.enabled:false}")
    private boolean keyVaultEnabled;

    @Value("${azure.storage.account-name:}")
    private String storageAccountName;

    @Value("${azure.storage.account-key:}")
    private String storageAccountKey;

    @Value("${azure.storage.blob-endpoint:}")
    private String blobEndpoint;

    /**
     * Creates Azure Key Vault Secret Client
     * Used for secure credential management
     */
    @Bean
    public SecretClient secretClient() {
        if (keyVaultEnabled && keyVaultUri != null && !keyVaultUri.isEmpty()) {
            return new SecretClientBuilder()
                    .vaultUrl(keyVaultUri)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();
        }
        return null;
    }

    /**
     * Creates Azure Blob Storage Service Client
     * Used for file storage operations
     */
    @Bean
    public BlobServiceClient blobServiceClient() {
        if (storageAccountName != null && !storageAccountName.isEmpty()) {
            if (blobEndpoint != null && !blobEndpoint.isEmpty()) {
                return new BlobServiceClientBuilder()
                        .endpoint(blobEndpoint)
                        .credential(new DefaultAzureCredentialBuilder().build())
                        .buildClient();
            } else if (storageAccountKey != null && !storageAccountKey.isEmpty()) {
                String connectionString = String.format(
                        "DefaultEndpointsProtocol=https;AccountName=%s;AccountKey=%s;EndpointSuffix=core.windows.net",
                        storageAccountName, storageAccountKey
                );
                return new BlobServiceClientBuilder()
                        .connectionString(connectionString)
                        .buildClient();
            }
        }
        return null;
    }
}
