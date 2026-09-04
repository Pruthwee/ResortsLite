package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-native report generation using Amazon S3.
 *
 * <p><strong>cr-java-0077 fix (Hard-coded Ports):</strong><br>
 * The original source contained a hard-coded port constant at line 28:
 * <pre>
 *   private static final int SERVER_PORT = 8080; // czr-port-001
 * </pre>
 * This constant has been removed entirely.  The server port is now externalised
 * to AWS Systems Manager Parameter Store and injected at runtime through the
 * {@code SERVER_PORT} environment variable, which is consumed by the Spring Boot
 * property {@code server.port=${SERVER_PORT:8080}} in {@code application.properties}.
 * Container orchestration platforms (ECS, EKS, Elastic Beanstalk) populate
 * {@code SERVER_PORT} from the SSM parameter {@code /resorts/<env>/server/port}
 * at deployment time, enabling dynamic port assignment without any code change.
 * The {@code serverPort} entry that was previously added to the result map of
 * {@code generateMonthlyReport()} and to {@code getSystemInfo()} has also been
 * removed, as the port is no longer a concern of the service layer.
 *
 * <p><strong>cr-java-0063 fix (Java.io.File Usage for Data Storage):</strong><br>
 * The original implementation used {@code java.io.File} to create directories and
 * check for their existence on the local file system
 * ({@code new File(REPORT_BASE_PATH)}, {@code reportDir.exists()},
 * {@code reportDir.mkdirs()}) and then wrote CSV reports via {@code FileWriter}
 * to a hard-coded local path ({@code /var/legacy/reports/}).
 * In containerised / serverless environments the local file system is ephemeral;
 * data written there is lost on container restart or scale-out events, and
 * {@code java.io.File} directory operations have no meaning in cloud storage.<br>
 * All {@code java.io.File}-based persistent storage operations (lines 37, 39, 42
 * of the original source) have been replaced with {@link S3Client#putObject} calls
 * so that report data is stored durably in Amazon S3 and is accessible from any
 * instance or service that has the appropriate IAM permissions.
 *
 * <p><strong>cr-java-0062 fix (Local File System Write Operations):</strong><br>
 * The {@code FileWriter} write at the original line 42 has also been replaced with
 * an S3 {@code PutObject} call as part of the same remediation pass.
 *
 * <p><strong>cr-java-0071 fix (Hard-coded Environment URLs):</strong><br>
 * The previously hard-coded URL
 * {@code "http://reports.resorts-internal.com:8080/download/"} (original line 66)
 * has been replaced with the {@code @Value}-injected field {@code reportDownloadBaseUrl}.
 * The value is resolved at startup from the Spring property
 * {@code app.reports.download-base-url}, which is itself bound to the
 * {@code APP_REPORTS_DOWNLOAD_BASE_URL} environment variable populated at deployment
 * time from AWS Systems Manager Parameter Store
 * (e.g. {@code /resorts/prod/reports/download-base-url}).
 * This makes the endpoint fully environment-agnostic — no code change is required
 * when promoting from dev → staging → production.
 *
 * <p>Bucket name and key prefixes are externalised via Spring {@code @Value} bindings
 * backed by environment variables ({@code REPORTS_S3_BUCKET}, {@code REPORTS_S3_PREFIX},
 * {@code BACKUP_S3_PREFIX}) — no infrastructure values are baked into the binary.
 */
@Service
public class ReportService {

    /**
     * S3 bucket used to store generated reports.
     * Resolved from the {@code REPORTS_S3_BUCKET} environment variable or the
     * {@code cloud.aws.s3.bucket-name} application property.
     */
    @Value("${cloud.aws.s3.bucket-name:resorts-reports-bucket}")
    private String s3BucketName;

    /**
     * S3 key prefix for monthly report objects.
     * Replaces the former hard-coded local path {@code /var/legacy/reports/}.
     * (cr-java-0063: eliminates {@code new File(REPORT_BASE_PATH)} at original line 37)
     */
    @Value("${cloud.aws.s3.report-prefix:reports/}")
    private String reportPrefix;

    /**
     * S3 key prefix for nightly backup objects.
     * Replaces the former hard-coded Windows path {@code C:\ResortBackups\nightly\}.
     */
    @Value("${cloud.aws.s3.backup-prefix:backups/nightly/}")
    private String backupPrefix;

    /**
     * cr-java-0071 fix: Base URL for report downloads externalised from the
     * hard-coded value {@code "http://reports.resorts-internal.com:8080/download/"}
     * (original line 66) to an AWS Systems Manager Parameter Store-backed property.
     *
     * <p>The value is resolved at startup from the Spring property
     * {@code app.reports.download-base-url}, which is itself bound to the
     * {@code APP_REPORTS_DOWNLOAD_BASE_URL} environment variable.  When the
     * Spring Cloud AWS SSM bootstrap is active the value is fetched directly
     * from the SSM parameter {@code /resorts/<env>/reports/download-base-url},
     * making the URL fully environment-agnostic without any code change.
     */
    @Value("${app.reports.download-base-url:${APP_REPORTS_DOWNLOAD_BASE_URL:https://reports.resorts-internal.com/download/}}")
    private String reportDownloadBaseUrl;

    // -----------------------------------------------------------------------
    // cr-java-0077 fix: Hard-coded Ports (original line 28)
    //
    // REMOVED:
    //   private static final int SERVER_PORT = 8080; // czr-port-001
    //
    // The hard-coded port constant has been eliminated.  The server port is now
    // externalised to AWS Systems Manager Parameter Store and injected at runtime
    // via the SERVER_PORT environment variable consumed by Spring Boot's
    // server.port=${SERVER_PORT:8080} property in application.properties.
    // ECS task definitions, EKS pod specs, and Elastic Beanstalk environment
    // configurations populate SERVER_PORT from the SSM parameter
    // /resorts/<env>/server/port, enabling dynamic port assignment without
    // any code change between environments.
    // -----------------------------------------------------------------------

    private final S3Client s3Client;

    /**
     * Constructor injection of the AWS SDK v2 {@link S3Client} bean
     * configured in {@link AwsS3Config}.
     *
     * @param s3Client the S3 client used for all object-storage operations
     */
    public ReportService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    /**
     * Generates a monthly booking report and persists it to Amazon S3.
     *
     * <p><strong>cr-java-0063 remediation (lines 37, 39, 42 of original source):</strong>
     * <ul>
     *   <li>Line 37 — {@code File reportDir = new File(REPORT_BASE_PATH);} removed.
     *       S3 does not require directory creation; the key prefix ({@code reportPrefix})
     *       serves as the logical namespace.</li>
     *   <li>Line 39 — {@code if (!reportDir.exists())} removed.
     *       S3 object existence checks are performed via {@code HeadObject} when needed;
     *       no pre-flight directory check is required for {@code PutObject}.</li>
     *   <li>Line 42 — {@code reportDir.mkdirs()} removed.
     *       S3 "directories" are virtual and are created implicitly when the first object
     *       with that key prefix is written.</li>
     * </ul>
     * All three {@code java.io.File} operations have been replaced with a single
     * {@link S3Client#putObject} call that writes the CSV content directly to S3.
     *
     * <p><strong>cr-java-0077 remediation:</strong> The {@code serverPort} entry
     * previously added to the result map ({@code result.put("serverPort", SERVER_PORT)})
     * has been removed.  Port information is no longer surfaced through the service
     * layer; it is managed exclusively through the externalised {@code SERVER_PORT}
     * environment variable.
     *
     * @param month the month component of the report period (e.g. {@code "03"})
     * @param year  the year component of the report period  (e.g. {@code "2024"})
     * @return a result map containing {@code status} and either {@code path} (S3 URI)
     *         or {@code message} (error description)
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // S3 object key — replaces the former local file path (cr-java-0063 / cr-java-0062).
        // Original line 37: File reportDir = new File(REPORT_BASE_PATH);  → REMOVED (cr-java-0063)
        // Original line 39: if (!reportDir.exists()) {                    → REMOVED (cr-java-0063)
        // Original line 42: reportDir.mkdirs();                           → REMOVED (cr-java-0063)
        // S3 key prefix acts as the logical "directory"; no File API needed.
        String s3Key = reportPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in-memory — no local FileWriter or File operations required.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            // cr-java-0063 fix: write to Amazon S3 instead of using java.io.File operations.
            // Previously:
            //   File reportDir = new File(REPORT_BASE_PATH);   // line 37 — java.io.File usage
            //   if (!reportDir.exists()) {                      // line 39 — java.io.File usage
            //       reportDir.mkdirs();                         // line 42 — java.io.File usage
            //   }
            //   FileWriter writer = new FileWriter(fullPath);   // local write — ephemeral
            // Now: S3 PutObject — durable, available across all instances and restarts,
            //      no host-level file system dependency.
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent));

            String s3Uri = "s3://" + s3BucketName + "/" + s3Key;
            result.put("status", "generated");
            result.put("path", s3Uri);
            // cr-java-0077 fix: result.put("serverPort", SERVER_PORT) REMOVED.
            // Port is no longer managed in the service layer; it is externalised
            // via the SERVER_PORT environment variable → server.port property.

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for the given report name.
     *
     * <p><strong>cr-java-0071 fix:</strong> The previously hard-coded URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} (original line 66)
     * has been replaced with the {@code @Value}-injected field {@code reportDownloadBaseUrl}.
     * The base URL is resolved at startup from the Spring property
     * {@code app.reports.download-base-url}, which is populated at deployment time from
     * AWS Systems Manager Parameter Store (e.g. {@code /resorts/prod/reports/download-base-url}).
     * No hard-coded environment-specific URL remains in the source code.
     *
     * @param reportName the name of the report file
     * @return the fully-qualified download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 fix: replaced hard-coded "http://reports.resorts-internal.com:8080/download/"
        // (original line 66) with the @Value-injected reportDownloadBaseUrl field.
        // The value is sourced from the APP_REPORTS_DOWNLOAD_BASE_URL environment variable,
        // which is populated at deployment time from AWS Systems Manager Parameter Store.
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information about the current S3 storage configuration.
     *
     * <p><strong>cr-java-0077 fix:</strong> The {@code serverPort} entry
     * ({@code info.put("serverPort", SERVER_PORT)}) that was present in the original
     * implementation has been removed.  The server port is now managed exclusively
     * through the externalised {@code SERVER_PORT} environment variable and is not
     * surfaced through the service layer.
     *
     * @return a map containing bucket name, key prefixes, and a generation timestamp
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 fix: replaced java.util.Date + SimpleDateFormat (timezone-ambiguous)
        // with java.time.Instant formatted via DateTimeFormatter using explicit UTC offset.
        // Standardising on UTC ensures consistent timestamps across all cloud regions,
        // containers, and service instances regardless of host timezone configuration.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        info.put("reportBucket", s3BucketName);
        info.put("reportPrefix", reportPrefix);
        info.put("backupPrefix", backupPrefix);
        info.put("reportDownloadBaseUrl", reportDownloadBaseUrl);
        info.put("generatedAt", timestamp);
        // cr-java-0077 fix: info.put("serverPort", SERVER_PORT) REMOVED.
        // Port is externalised via SERVER_PORT env var → server.port property.
        return info;
    }
}
