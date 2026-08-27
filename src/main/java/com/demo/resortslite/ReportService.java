package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService handles report generation and storage using Amazon S3
 * for cloud-native, durable, and scalable object storage.
 *
 * <p>All file system dependencies have been replaced with Amazon S3 operations
 * using AWS SDK for Java v2. Hard-coded paths, ports, and URLs have been
 * externalized to AWS Systems Manager Parameter Store and environment variables.</p>
 */
@Service
public class ReportService {

    // Blocker cr-java-0061 / cr-java-0062 / cr-java-0063:
    // Hard-coded absolute file paths (/var/legacy/reports/, C:\ResortBackups\nightly\)
    // replaced with Amazon S3 bucket name injected from environment variable.
    @Value("${aws.s3.bucket-name:resorts-lite-reports}")
    private String s3BucketName;

    @Value("${aws.s3.region:us-east-1}")
    private String awsRegion;

    // Blocker cr-java-0077: Hard-coded SERVER_PORT replaced with environment variable injection.
    @Value("${server.port:8080}")
    private int serverPort;

    // Blocker cr-java-0071: Hard-coded report download URL replaced with SSM Parameter Store reference.
    @Value("${aws.ssm.report-download-url-param:/resortslite/report/download-url}")
    private String reportDownloadUrlParam;

    /**
     * Generates a monthly report and uploads it to Amazon S3.
     *
     * <p>Replaces local file system write operations (java.io.File, FileWriter)
     * with Amazon S3 PutObject calls for cloud-native durable storage.</p>
     *
     * @param month the month for the report
     * @param year  the year for the report
     * @return a map containing the operation status and S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // Blocker cr-java-0061: Hard-coded REPORT_BASE_PATH replaced with S3 key prefix
        String s3Key = "reports/resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Blocker cr-java-0062 / cr-java-0063:
            // Local FileWriter / java.io.File replaced with Amazon S3 PutObject via AWS SDK v2
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            S3Client s3Client = S3Client.builder()
                    .region(Region.of(awsRegion))
                    .build();

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest,
                    RequestBody.fromBytes(csvContent.getBytes(StandardCharsets.UTF_8)));

            result.put("status", "generated");
            // Blocker cr-java-0061: path now refers to S3 URI instead of local file path
            result.put("s3Uri", "s3://" + s3BucketName + "/" + s3Key);
            // Blocker cr-java-0077: serverPort now injected from environment variable
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL by retrieving the base URL from
     * AWS Systems Manager Parameter Store.
     *
     * <p>Replaces the hard-coded HTTP URL with a value retrieved from
     * SSM Parameter Store, enabling environment-agnostic deployments.</p>
     *
     * @param reportName the name of the report file
     * @return the full download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // Blocker cr-java-0071: Hard-coded "http://reports.resorts-internal.com:8080/download/"
        // replaced with URL retrieved from AWS Systems Manager Parameter Store
        try {
            SsmClient ssmClient = SsmClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            GetParameterRequest paramRequest = GetParameterRequest.builder()
                    .name(reportDownloadUrlParam)
                    .withDecryption(false)
                    .build();

            GetParameterResponse paramResponse = ssmClient.getParameter(paramRequest);
            String baseUrl = paramResponse.parameter().value();
            return baseUrl + "/" + reportName;

        } catch (Exception e) {
            // Fallback: construct S3 pre-signed URL reference when SSM is unavailable
            return "https://" + s3BucketName + ".s3." + awsRegion
                    + ".amazonaws.com/reports/" + reportName;
        }
    }

    /**
     * Returns system information using cloud-native configuration values.
     *
     * <p>Hard-coded file paths replaced with S3 bucket references.
     * java.util.Date replaced with java.time.Instant (UTC) per blocker cr-java-0111.</p>
     *
     * @return a map containing system configuration and timestamp information
     */
    public Map<String, Object> getSystemInfo() {
        // Blocker cr-java-0111: java.util.Date / SimpleDateFormat replaced with
        // java.time.Instant standardized on UTC to eliminate timezone inconsistencies
        // across distributed cloud instances and regions.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        // Blocker cr-java-0061: Hard-coded REPORT_BASE_PATH replaced with S3 bucket reference
        info.put("reportBucket", s3BucketName);
        info.put("reportPrefix", "reports/");
        // Blocker cr-java-0061: Hard-coded BACKUP_PATH replaced with S3 backup prefix
        info.put("backupPrefix", "backups/nightly/");
        // Blocker cr-java-0077: serverPort now injected from environment variable
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
