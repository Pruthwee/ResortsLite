package com.demo.resortslite;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // S3 bucket and prefix from environment (replaces hard-coded /var/legacy/reports and C:\ResortBackups)
    @Value("${S3_REPORTS_BUCKET:reports-bucket}")
    private String s3BucketName;

    @Value("${S3_REPORTS_PREFIX:reports/}")
    private String s3ReportsPrefix;

    // Server port read from environment, old value 8080 as default
    private static final int SERVER_PORT = Integer.parseInt(
            System.getenv().getOrDefault("SERVER_PORT", "8080"));

    // Lazily-initialized S3 client (built on first use inside try, never in static initializer)
    private S3Client s3Client;

    private S3Client getS3Client() {
        if (s3Client == null) {
            s3Client = S3Client.create();
        }
        return s3Client;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String s3Key = s3ReportsPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            getS3Client().putObject(putRequest, RequestBody.fromString(csvContent.toString()));

            result.put("status", "generated");
            result.put("path", s3Key);
            result.put("serverPort", SERVER_PORT);

        } catch (SdkException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP URL
        // hardcoded for report download. Cloud security standards enforce HTTPS.
        return "http://reports.resorts-internal.com:8080/download/" + reportName; // cr-java-0088
    }

    public Map<String, Object> getSystemInfo() {
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", s3BucketName + "/" + s3ReportsPrefix);
        info.put("backupPath", s3BucketName + "/" + s3ReportsPrefix);
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
