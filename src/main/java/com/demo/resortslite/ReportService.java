package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // FIXED cr-java-0061: Replaced hard-coded file paths with S3 configuration
    // Using environment variables and Spring properties for cloud-native configuration
    @Value("${aws.s3.bucket.name}")
    private String s3BucketName;

    @Value("${aws.s3.region}")
    private String awsRegion;

    @Value("${aws.s3.reports.prefix}")
    private String reportsPrefix;

    @Value("${aws.s3.backups.prefix}")
    private String backupsPrefix;

    @Value("${SERVER_PORT}")
    private int serverPort;

    private S3Client s3Client;

    @PostConstruct
    public void initializeS3Client() {
        // Initialize S3 client with default credentials provider (uses IAM roles in AWS)
        s3Client = S3Client.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @PreDestroy
    public void closeS3Client() {
        if (s3Client != null) {
            s3Client.close();
        }
    }

    /**
     * Generates a monthly report and stores it in Amazon S3.
     * FIXED cr-java-0061: Replaced local file system operations with S3 storage.
     *
     * @param month The month for the report
     * @param year The year for the report
     * @return Map containing the status and S3 location of the generated report
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // FIXED cr-java-0061 Line 23: Replaced REPORT_BASE_PATH with S3 key prefix
        String s3Key = reportsPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Generate CSV content in memory instead of writing to local file system
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
            
            writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            writer.flush();
            writer.close();

            byte[] reportContent = outputStream.toByteArray();

            // FIXED cr-java-0061 Line 37 & 42: Upload to S3 instead of local file system
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(reportContent));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);
            result.put("s3Uri", "s3://" + s3BucketName + "/" + s3Key);
            result.put("serverPort", serverPort);

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", "S3 Error: " + e.awsErrorDetails().errorMessage());
        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", "IO Error: " + e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL.
     * FIXED cr-java-0088: Changed to HTTPS for cloud security standards.
     *
     * @param reportName The name of the report
     * @return The download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // FIXED cr-java-0088: Changed HTTP to HTTPS for cloud security
        return "https://reports.resorts-internal.com:" + serverPort + "/download/" + reportName;
    }

    /**
     * Returns system information including S3 configuration.
     * FIXED cr-java-0061: Replaced hard-coded file paths with S3 configuration.
     *
     * @return Map containing system information
     */
    public Map<String, Object> getSystemInfo() {
        // FIXED cr-java-0111: Replaced java.util.Date/SimpleDateFormat with java.time API using UTC
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        
        // FIXED cr-java-0061: Return S3 configuration instead of hard-coded paths
        info.put("s3BucketName", s3BucketName);
        info.put("s3Region", awsRegion);
        info.put("reportsPrefix", reportsPrefix);
        info.put("backupsPrefix", backupsPrefix);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        
        return info;
    }
}
