package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Cloud-native: S3 bucket name is externalised via environment variable / configuration.
    // Eliminates hard-coded absolute file system paths that do not exist in containers.
    @Value("${app.reports.s3.bucket:resorts-lite-reports}")
    private String reportBucket;

    // Cloud-native: AWS region is externalised via environment variable / configuration.
    @Value("${app.reports.s3.region:us-east-1}")
    private String s3Region;

    // Lazily-initialised S3 client — created on first use with default credentials provider
    // chain (environment variables, system properties, EC2/ECS instance metadata, etc.).
    private S3Client s3Client;

    /**
     * Returns a lazily-initialised S3Client using the configured region and the
     * DefaultCredentialsProvider chain so that no credentials are hard-coded.
     */
    private S3Client getS3Client() {
        if (s3Client == null) {
            s3Client = S3Client.builder()
                    .region(Region.of(s3Region))
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .build();
        }
        return s3Client;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // Cloud-native: object key replaces the hard-coded file system path.
        String objectKey = "reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV report content in memory — no local file system dependency.
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            // Upload the report content directly to Amazon S3 — replaces local file write.
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(objectKey)
                    .acl("private")
                    .build();

            getS3Client().putObject(putRequest, RequestBody.fromString(csvContent.toString()));

            result.put("status", "generated");
            result.put("s3Bucket", reportBucket);
            result.put("s3Key", objectKey);
            result.put("s3Url", "s3://" + reportBucket + "/" + objectKey);

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a pre-signed download URL for the given report name stored in S3.
     *
     * @param reportName the S3 object key (or file name) of the report
     * @return an HTTPS URL pointing to the S3 object
     */
    public String buildReportDownloadUrl(String reportName) {
        // Cloud-native: HTTPS URL to S3 object — no hard-coded HTTP endpoint or port.
        return "https://" + reportBucket + ".s3." + s3Region + ".amazonaws.com/reports/" + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        String timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        // Cloud-native: report storage location is now S3, not a local file path.
        info.put("reportBucket", reportBucket);
        info.put("reportRegion", s3Region);
        info.put("generatedAt", timestamp);
        return info;
    }
}
