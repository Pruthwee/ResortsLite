package com.demo.resortslite;

import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // cr-java-0061: Hard-coded file paths replaced with S3 bucket names from environment
    private static final String REPORT_S3_BUCKET = System.getenv().getOrDefault("APP_S3_BUCKET_NAME", "resorts-reports");
    // cr-java-0061: Hard-coded file path replaced with S3 bucket name from environment
    private static final String BACKUP_S3_BUCKET = System.getenv().getOrDefault("APP_S3_BACKUP_BUCKET_NAME", "resorts-backups");
    // cr-java-0077: Hard-coded port replaced with environment variable
    private static final int SERVER_PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

    private S3Client s3Client;

    private S3Client getS3Client() {
        if (s3Client == null) {
            s3Client = S3Client.create();
        }
        return s3Client;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String s3Key = "reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0061, cr-java-0062, cr-java-0063: Local file write replaced with S3 PutObject
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(REPORT_S3_BUCKET)
                .key(s3Key)
                .contentType("text/csv")
                .build();

            getS3Client().putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("path", "s3://" + REPORT_S3_BUCKET + "/" + s3Key);
            result.put("serverPort", SERVER_PORT);

        } catch (SdkException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071: Hard-coded URL replaced with environment variable
        String baseUrl = System.getenv().getOrDefault("REPORTS_API_URL", "http://reports.resorts-internal.com:8080/download/");
        return baseUrl + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        // cr-java-0111: java.util.Date/SimpleDateFormat replaced with java.time (UTC)
        String timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", "s3://" + REPORT_S3_BUCKET + "/reports/");
        info.put("backupPath", "s3://" + BACKUP_S3_BUCKET + "/backups/");
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
