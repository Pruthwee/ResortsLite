package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    @Autowired
    private ConfigurationManager configManager;

    @Value("${aws.s3.bucket.name}")
    private String bucketName;

    @Value("${server.port:8080}")
    private int serverPort;

    private S3Client s3Client;
    private S3Client s3Client() {
        if (s3Client == null) {
            s3Client = S3Client.builder().build();
        }
        return s3Client;
    }

    private String getS3ReportKey(String fileName) {
        return "reports/" + fileName;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String s3Key = getS3ReportKey(fileName);

        Map<String, Object> result = new HashMap<>();

        try {
            StringBuilder csvData = new StringBuilder();
            csvData.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvData.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvData.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client().putObject(putRequest, RequestBody.fromString(csvData.toString()));

            result.put("status", "generated");
            result.put("path", "s3://" + bucketName + "/" + s3Key);
            result.put("serverPort", serverPort);

        } catch (SdkException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        String baseUrl = configManager.getParameter("reports/download/url",
                "http://reports.resorts-internal.com:8080/download/");
        return baseUrl + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.of("UTC"))
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", "s3://" + bucketName + "/reports/");
        info.put("backupPath", "s3://" + bucketName + "/backups/");
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
