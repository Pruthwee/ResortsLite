package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // S3 bucket name injected from environment variable / application properties.
    // Replaces hard-coded absolute path "/var/legacy/reports/" (cr-java-0061 line 23).
    @Value("${cloud.aws.s3.bucket-name:resorts-lite-reports}")
    private String s3BucketName;

    // S3 key prefix replaces the former REPORT_BASE_PATH constant.
    // Replaces hard-coded absolute path "/var/legacy/reports/" (cr-java-0061 line 23).
    @Value("${cloud.aws.s3.report-prefix:reports/}")
    private String reportPrefix;

    // S3 key prefix for backups replaces the former Windows-style BACKUP_PATH constant.
    // Replaces hard-coded path "C:\\ResortBackups\\nightly\\" (cr-java-0061 line 23).
    @Value("${cloud.aws.s3.backup-prefix:backups/nightly/}")
    private String backupPrefix;

    // AWS region injected from environment variable.
    @Value("${cloud.aws.region:us-east-1}")
    private String awsRegion;

    /**
     * AWS SSM Parameter Store parameter name for the server port.
     * Injected from application properties / environment variable.
     * Replaces the hard-coded port constant 8080 (cr-java-0077 line 28).
     * Override via environment variable: SSM_SERVER_PORT_PARAM
     */
    @Value("${app.ssm.server-port-param:/resortslite/server/port}")
    private String serverPortParamName;

    /**
     * Retrieves the server port from AWS Systems Manager Parameter Store.
     * Replaces the hard-coded SERVER_PORT = 8080 constant (cr-java-0077 line 28)
     * with a cloud-native, environment-agnostic configuration lookup, enabling
     * dynamic port assignment required by ECS, EKS, and Elastic Beanstalk.
     *
     * @return the server port value stored in SSM Parameter Store
     */
    private int getServerPortFromSsm() {
        SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();

        GetParameterRequest request = GetParameterRequest.builder()
                .name(serverPortParamName)
                .withDecryption(false)
                .build();

        GetParameterResponse response = ssmClient.getParameter(request);
        String portValue = response.parameter().value();
        return Integer.parseInt(portValue);
    }

    /**
     * AWS SSM Parameter Store parameter name for the report download base URL.
     * Injected from application properties / environment variable.
     * Replaces the hard-coded URL "http://reports.resorts-internal.com:8080/download/"
     * (cr-java-0071 line 66).
     */
    @Value("${app.ssm.report-download-url-param:/resortslite/reports/download-base-url}")
    private String reportDownloadUrlParamName;

    /**
     * Retrieves the report download base URL from AWS Systems Manager Parameter Store.
     * This replaces the former hard-coded URL (cr-java-0071 line 66) with a
     * cloud-native, environment-agnostic configuration lookup.
     *
     * @return the report download base URL stored in SSM Parameter Store
     */
    private String getReportDownloadBaseUrlFromSsm() {
        SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();

        GetParameterRequest request = GetParameterRequest.builder()
                .name(reportDownloadUrlParamName)
                .withDecryption(false)
                .build();

        GetParameterResponse response = ssmClient.getParameter(request);
        return response.parameter().value();
    }

    /**
     * Generates a monthly CSV report and uploads it to Amazon S3.
     * Replaces local FileWriter / File operations that used hard-coded absolute paths
     * (cr-java-0061 lines 37 and 42).
     *
     * @param month the month for the report (e.g. "03")
     * @param year  the year for the report  (e.g. "2024")
     * @return a result map containing status and the S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // S3 object key replaces the former local fullPath variable (cr-java-0061 line 37).
        String s3Key = reportPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency.
            // Replaces File / FileWriter operations that relied on REPORT_BASE_PATH
            // (cr-java-0061 lines 37 and 42).
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            // Upload the report directly to Amazon S3 using AWS SDK v2.
            S3Client s3Client = S3Client.builder()
                    .region(Region.of(awsRegion))
                    .build();

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest,
                    RequestBody.fromString(csvContent.toString()));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);
            // FIX cr-java-0077: port retrieved from AWS SSM Parameter Store at runtime.
            result.put("serverPort", getServerPortFromSsm());

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the report download URL using the base URL retrieved from
     * AWS Systems Manager Parameter Store.
     * Replaces the former hard-coded plain-HTTP URL
     * "http://reports.resorts-internal.com:8080/download/" (cr-java-0071 line 66)
     * with a cloud-native, environment-agnostic configuration lookup.
     *
     * @param reportName the name of the report to download
     * @return the full download URL for the given report name
     */
    public String buildReportDownloadUrl(String reportName) {
        // FIX cr-java-0071: Replaced hard-coded URL "http://reports.resorts-internal.com:8080/download/"
        // with a value retrieved from AWS Systems Manager Parameter Store.
        // The parameter name is configured via app.ssm.report-download-url-param in
        // application.properties (or the SSM_REPORT_DOWNLOAD_URL_PARAM environment variable),
        // enabling environment-agnostic deployments across dev, staging, and production.
        String baseUrl = getReportDownloadBaseUrlFromSsm(); // cr-java-0071 fixed
        return baseUrl + reportName;
    }

    /**
     * Returns system information using cloud-native configuration values.
     * Replaces references to hard-coded REPORT_BASE_PATH and BACKUP_PATH constants
     * (cr-java-0061 line 23).
     *
     * @return a map of system information entries
     */
    public Map<String, Object> getSystemInfo() {
        // FIX cr-java-0111: Replaced java.util.Date + SimpleDateFormat with java.time API.
        // Instant.now() captures the current moment in UTC, eliminating server-local timezone
        // dependencies that cause scheduling failures in distributed cloud environments.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // S3 bucket and key prefixes replace the former hard-coded local paths.
        info.put("s3Bucket", s3BucketName);
        info.put("reportPrefix", reportPrefix);
        info.put("backupPrefix", backupPrefix);
        // FIX cr-java-0077: port retrieved from AWS SSM Parameter Store at runtime.
        info.put("serverPort", getServerPortFromSsm());
        info.put("generatedAt", timestamp);
        return info;
    }
}
