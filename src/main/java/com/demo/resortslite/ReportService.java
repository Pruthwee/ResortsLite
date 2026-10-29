package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Reports are stored in Amazon S3 (bucket from APP_S3_BUCKET) under this key prefix.
    private static final String REPORT_BASE_PATH = "reports/";

    // Nightly backups live under this S3 key prefix of the same bucket.
    private static final String BACKUP_PATH = "backups/nightly/";

    // Port reported by the application, read from SERVER_PORT, default 8080.
    private static final int SERVER_PORT = readServerPort();

    // Name of the Parameter Store parameter (under APP_PARAMETER_PATH) holding the report download URL.
    private static final String REPORT_URL_PARAMETER = "report-download-url";

    @Value("${app.report.download.url}")
    private String reportDownloadUrl;

    // Clients are built lazily on first use and closed on shutdown.
    private S3Client s3Client;
    private SsmClient ssmClient;

    private static int readServerPort() {
        try {
            return Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "8080"));
        } catch (NumberFormatException e) {
            return 8080;
        }
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String key = REPORT_BASE_PATH + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            String bucket = System.getenv("APP_S3_BUCKET");
            if (bucket == null || bucket.isEmpty()) {
                throw new IOException("APP_S3_BUCKET is not set");
            }

            StringBuilder content = new StringBuilder();
            content.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            content.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            content.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            if (s3Client == null) {
                s3Client = S3Client.create();
            }
            s3Client.putObject(PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType("text/csv")
                    .build(), RequestBody.fromString(content.toString()));

            result.put("status", "generated");
            result.put("path", "s3://" + bucket + "/" + key);
            result.put("serverPort", SERVER_PORT);

        } catch (IOException | SdkException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    // VIOLATION [Code Sustainability / Medium]: No JavaDoc or method documentation.
    // Missing documentation is flagged across all public methods in the codebase.
    // This increases onboarding time and transformation risk for automated tools.
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        String baseUrl = reportDownloadUrl;
        try {
            String parameterPath = System.getenv("APP_PARAMETER_PATH");
            if (parameterPath != null && !parameterPath.isEmpty()) {
                if (!parameterPath.endsWith("/")) {
                    parameterPath = parameterPath + "/";
                }
                if (ssmClient == null) {
                    ssmClient = SsmClient.create();
                }
                baseUrl = ssmClient.getParameter(GetParameterRequest.builder()
                        .name(parameterPath + REPORT_URL_PARAMETER)
                        .withDecryption(true)
                        .build()).parameter().value();
            }
        } catch (SdkException e) {
            baseUrl = reportDownloadUrl;
        }
        return baseUrl + reportName;
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", s3Location(REPORT_BASE_PATH));
        info.put("backupPath", s3Location(BACKUP_PATH));
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }

    private static String s3Location(String prefix) {
        String bucket = System.getenv("APP_S3_BUCKET");
        return (bucket == null || bucket.isEmpty()) ? prefix : "s3://" + bucket + "/" + prefix;
    }

    @PreDestroy
    void closeClients() {
        if (s3Client != null) {
            s3Client.close();
        }
        if (ssmClient != null) {
            ssmClient.close();
        }
    }
}
