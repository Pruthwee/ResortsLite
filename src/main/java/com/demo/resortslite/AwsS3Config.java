package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * AWS S3 configuration.
 * <p>
 * Provides a Spring-managed {@link S3Client} bean used by {@link ReportService}
 * to store and retrieve report files from Amazon S3, replacing the previous
 * hard-coded local file-system paths (cr-java-0061).
 * <p>
 * The AWS region is externalised via the {@code cloud.aws.region} property
 * (or the {@code AWS_REGION} environment variable) so that no infrastructure
 * values are baked into the application binary.
 */
@Configuration
public class AwsS3Config {

    @Value("${cloud.aws.region:us-east-1}")
    private String awsRegion;

    /**
     * Creates and exposes an AWS SDK v2 {@link S3Client} bean.
     * Credentials are resolved automatically from the default credential-provider
     * chain (IAM role, environment variables, ~/.aws/credentials, etc.).
     *
     * @return a configured {@link S3Client} instance
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
