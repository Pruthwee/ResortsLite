package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
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

    // cr-java-0061/0062/0063: Hard-coded file paths replaced with Amazon S3 object storage.
    // Bucket and key prefix come from AWS environment variables.
    @Value("${aws.s3.bucket.name}")
    private String s3BucketName;

    @Value("${aws.s3.key.prefix:reports/}")
    private String s3KeyPrefix;

    // cr-java-0077: Server port read from environment variable with old default
    private static final int SERVER_PORT =
            Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "8080"));

    // Lazy S3 client, created on first use inside the old try
    private S3Client s3Client;

    private S3Client getS3Client() {
        if (s3Client == null) {
            s3Client = S3Client.create();
        }
        return s3Client;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String s3Key = s3KeyPrefix + fileName;

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
            result.put("path", s3BucketName + "/" + s3Key);
            result.put("serverPort", SERVER_PORT);

        } catch (SdkException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0088: Plain HTTP URL replaced with HTTPS scheme
        return "https://reports.resorts-internal.com/download/" + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        // cr-java-0111: java.util.Date/SimpleDateFormat replaced with java.time API in UTC
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", s3BucketName + "/" + s3KeyPrefix);
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
