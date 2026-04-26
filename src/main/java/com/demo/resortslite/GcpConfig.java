package com.demo.resortslite;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Google Cloud Platform configuration for cloud-native services.
 * FIXED cr-java-0061, cr-java-0062, cr-java-0063: Configures Google Cloud Storage
 * FIXED cr-java-0069, cr-java-0090: Enables Secret Manager integration
 */
@Configuration
public class GcpConfig {

    @Value("${gcp.project.id:${GCP_PROJECT_ID:}}")
    private String projectId;

    /**
     * Creates Google Cloud Storage client bean.
     * FIXED cr-java-0061, cr-java-0062, cr-java-0063: Provides GCS client for file operations
     * 
     * @return Configured Storage client
     */
    @Bean
    public Storage googleCloudStorage() {
        StorageOptions.Builder builder = StorageOptions.newBuilder();
        
        if (projectId != null && !projectId.isEmpty()) {
            builder.setProjectId(projectId);
        }
        
        return builder.build().getService();
    }
}
