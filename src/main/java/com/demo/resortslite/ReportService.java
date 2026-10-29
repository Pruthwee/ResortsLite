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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Reports and nightly backups are kept in Amazon S3 (bucket from APP_S3_BUCKET);
    // these are the key prefixes inside that bucket.
    private static final String REPORT_KEY_PREFIX = "reports/";
    private static final String BACKUP_KEY_PREFIX = "backups/nightly/";

    // The application's own listen port, the same one Spring Boot binds (server.port=${PORT:8080}).
    private static final int SERVER_PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

    private static final String REPORT_DOWNLOAD_URL_PARAMETER = "report-download-url";

    @Value("${app.report.download.url}")
    private String reportDownloadUrl;

    private S3Client s3Client;
    private SsmClient ssmClient;

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String key = REPORT_KEY_PREFIX + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            String bucket = System.getenv("APP_S3_BUCKET");
            if (bucket == null || bucket.isEmpty()) {
                throw new IOException("APP_S3_BUCKET is not set");
            }

            StringBuilder csv = new StringBuilder();
            csv.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csv.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csv.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType("text/csv")
                    .build();
            s3().putObject(putRequest, RequestBody.fromString(csv.toString()));

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
        return resolveUrl(REPORT_DOWNLOAD_URL_PARAMETER, reportDownloadUrl) + reportName;
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .format(ZonedDateTime.now(ZoneOffset.UTC));
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", REPORT_KEY_PREFIX);
        info.put("backupPath", BACKUP_KEY_PREFIX);
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }

    /** Created on first use, so the application starts without AWS. */
    private synchronized S3Client s3() {
        if (s3Client == null) {
            s3Client = S3Client.create();
        }
        return s3Client;
    }

    private synchronized SsmClient ssm() {
        if (ssmClient == null) {
            ssmClient = SsmClient.create();
        }
        return ssmClient;
    }

    /**
     * Reads the whole URL from Parameter Store under APP_PARAMETER_PATH; when the path is not set
     * or the call fails, the URL from application.properties is used, as before.
     */
    private String resolveUrl(String parameterName, String fallback) {
        String path = System.getenv("APP_PARAMETER_PATH");
        if (path != null && !path.isEmpty()) {
            try {
                String name = path.endsWith("/") ? path + parameterName : path + "/" + parameterName;
                return ssm().getParameter(GetParameterRequest.builder()
                        .name(name)
                        .withDecryption(true)
                        .build()).parameter().value();
            } catch (SdkException e) {
                // fall through to the configured URL
            }
        }
        return fallback;
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
