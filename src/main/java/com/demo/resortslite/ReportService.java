package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

    @Value("${aws.s3.bucket:reports-bucket}")
    private String reportBucket;

    @Value("${aws.s3.backup-bucket:backups-bucket}")
    private String backupBucket;

    @Value("${SERVER_PORT:8080}")

    public ReportService() {
        this.s3Client = S3Client.create();
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        Map<String, Object> result = new HashMap<>();

        try {
            String content = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n" +
                             "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n" +
                             "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putOb = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(fileName)
                    .build();

            s3Client.putObject(putOb, RequestBody.fromString(content, StandardCharsets.UTF_8));

            result.put("status", "generated");
            result.put("path", "s3://" + reportBucket + "/" + fileName);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        // Fixed: Externalized URL using AWS Systems Manager Parameter Store (via @Value)
        // cr-java-0071
        return reportDownloadUrl + reportName;
        String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
