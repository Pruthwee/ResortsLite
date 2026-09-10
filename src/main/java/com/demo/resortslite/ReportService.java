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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // VIOLATION czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute path.
    // /var/legacy/reports does not exist in a Docker container image. Breaks containerisation.
    // Must use volume mounts, cloud object storage (S3 / Azure Blob), or environment variable.
    private static final String REPORT_BASE_PATH = "/var/legacy/reports/"; // czr-java-001

    // VIOLATION czr-java-001 [Software Portability / Mandatory]: Windows-style absolute path
    // will fail on any Linux-based container or cloud host. Hard dependency on OS path structure.
    private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\"; // czr-java-001

    // REMEDIATION cr-java-0077: Replace hard-coded port with AWS Parameter Store and
    // environment variable injection. Container orchestration (ECS / EKS) dynamically
    // assigns ports. The port is now resolved at runtime from the SERVER_PORT environment
    // variable, which can be populated from AWS Systems Manager Parameter Store.
    @Value("${app.server.port:8080}")
    private int serverPort;

    @Value("${app.s3.bucket:resorts-lite-reports}")
    private String s3BucketName;

    @Value("${app.s3.region:us-east-1}")
    private String s3Region;

    private S3Client s3Client;

    @PostConstruct
    public void init() {
        s3Client = S3Client.builder()
                .region(Region.of(s3Region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Build report content
            StringBuilder reportContent = new StringBuilder();
            reportContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            reportContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            reportContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            // REMEDIATION cr-java-0062: Replace local file system write with Amazon S3
            // Local file writes are ephemeral in containerized/cloud environments.
            // Using S3 ensures data durability, availability, and scalability.
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(fileName)
                    .build();
            s3Client.putObject(putRequest, RequestBody.fromString(reportContent.toString()));

            String s3ObjectUrl = "s3://" + s3BucketName + "/" + fileName;

            result.put("status", "generated");
            result.put("path", s3ObjectUrl);
            result.put("serverPort", serverPort);

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    // VIOLATION [Code Sustainability / Medium]: No JavaDoc or method documentation.
    // Missing documentation is flagged across all public methods in the codebase.
    // This increases onboarding time and transformation risk for automated tools.
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP URL
        // hardcoded for report download. Cloud security standards enforce HTTPS.
        return "http://reports.resorts-internal.com:8080/download/" + reportName; // cr-java-0088
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        String timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", REPORT_BASE_PATH);  // czr-java-001
        info.put("backupPath", BACKUP_PATH);        // czr-java-001
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
