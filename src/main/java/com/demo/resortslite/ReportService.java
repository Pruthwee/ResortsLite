package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // S3 bucket name and key prefix are externalised to environment variables /
    // application properties — no hardcoded local paths.
    @Value("${cloud.aws.s3.bucket-name:resorts-lite-reports}")
    private String s3BucketName;

    @Value("${cloud.aws.s3.report-prefix:reports/}")
    private String reportPrefix;

    @Value("${cloud.aws.s3.backup-prefix:backups/nightly/}")
    private String backupPrefix;

    @Value("${cloud.aws.region.static:us-east-1}")
    private String awsRegion;

    /**
     * AWS SSM Parameter Store parameter name for the report download base URL.
     * Defaults to "/resortslite/reports/download-base-url" if not overridden via
     * the APP_SSM_REPORT_DOWNLOAD_URL_PARAM environment variable.
     */
    @Value("${app.ssm.report-download-url-param:/resortslite/reports/download-base-url}")
    private String reportDownloadUrlSsmParam;

    // Server port is no longer hardcoded in application logic; it is managed
    // via the 'server.port' property / PORT environment variable.

    /**
     * Retrieves a configuration value from AWS Systems Manager Parameter Store.
     * Falls back to the provided defaultValue if the parameter cannot be fetched
     * (e.g., when running outside AWS during local development).
     *
     * @param paramName    SSM parameter name/path
     * @param defaultValue fallback value used when SSM is unavailable
     * @return resolved configuration value string
     */
    private String resolveFromSsm(String paramName, String defaultValue) {
        try {
            SsmClient ssmClient = SsmClient.builder()
                    .region(Region.of(awsRegion))
                    .build();
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(paramName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (SsmException e) {
            // Log and fall back to the default so the application remains functional
            // when running outside AWS (e.g., local development).
            return defaultValue;
        }
    }

    /**
     * Generates a monthly CSV report and uploads it to Amazon S3.
     * Replaces the previous local FileWriter write to /var/legacy/reports/.
     *
     * @param month two-digit month string (e.g. "03")
     * @param year  four-digit year string  (e.g. "2024")
     * @return result map containing upload status and S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String s3Key = reportPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        // Build CSV content in-memory — no local file system dependency
        StringBuilder csvContent = new StringBuilder();
        csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
        csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
        csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

        try {
            // Upload the CSV content directly to Amazon S3 for durable, cloud-native storage
            S3Client s3Client = S3Client.builder()
                    .region(Region.of(awsRegion))
                    .build();

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putObjectRequest,
                    RequestBody.fromString(csvContent.toString()));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", e.awsErrorDetails().errorMessage());
        }

        return result;
    }

    /**
     * Builds a secure download URL for a report by retrieving the base URL from
     * AWS Systems Manager Parameter Store (cr-java-0071 fix).
     *
     * <p>The SSM parameter name is itself externalised via the
     * {@code app.ssm.report-download-url-param} application property (overridable
     * through the {@code APP_SSM_REPORT_DOWNLOAD_URL_PARAM} environment variable),
     * so no environment-specific URL is ever hard-coded in source code.</p>
     *
     * @param reportName the name of the report object
     * @return fully-qualified download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX: The hard-coded environment-specific URL
        // "http://reports.resorts-internal.com:8080/download/" has been replaced with
        // a value retrieved at runtime from AWS Systems Manager Parameter Store.
        // The SSM parameter stores the HTTPS base URL for the environment
        // (e.g., "https://reports.resorts-prod.example.com/download/"), ensuring
        // environment-agnostic deployments without any code changes between environments.
        String baseUrl = resolveFromSsm(
                reportDownloadUrlSsmParam,
                "https://" + s3BucketName + ".s3." + awsRegion + ".amazonaws.com/" + reportPrefix);
        return baseUrl + reportName;
    }

    /**
     * Returns system information using cloud-native configuration values.
     * Replaces hardcoded local paths with S3 bucket/prefix references.
     *
     * @return map of system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX: Replaced java.util.Date / SimpleDateFormat with java.time API
        // standardised on UTC to avoid timezone inconsistencies across cloud regions/containers.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // Expose S3 locations instead of ephemeral local paths
        info.put("reportBucket", s3BucketName);
        info.put("reportPrefix", reportPrefix);
        info.put("backupPrefix", backupPrefix);
        info.put("generatedAt", timestamp);
        return info;
    }
}
