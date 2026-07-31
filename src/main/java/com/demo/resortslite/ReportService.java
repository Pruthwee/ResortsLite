package com.demo.resortslite;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    @Value("${aws.s3.bucket:resorts-reports-bucket}")
    private String reportBucket;

    @Value("${reports.download.url}")
    private String reportsDownloadUrl;
    
    @Value("${server.port:8080}")
    private int serverPort;

    @Autowired
    private S3Client s3Client;

    public Map<String, Object> generateMonthlyReport(int month, int year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        
        Map<String, Object> result = new HashMap<>();

        try {
            String content = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n" +
                             "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n" +
                             "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(fileName)
                    .build();
            
            s3Client.putObject(putObjectRequest, RequestBody.fromString(content, StandardCharsets.UTF_8));
            
            result.put("status", "success");
            result.put("fileName", fileName);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public Map<String, Object> getReportMetadata() {
        Map<String, Object> info = new HashMap<>();
        
        // FIXED: Replaced java.util.Date/SimpleDateFormat with java.time API and standardized on UTC
        ZonedDateTime nowUtc = ZonedDateTime.now(ZoneOffset.UTC);
        String timestamp = nowUtc.format(DateTimeFormatter.ISO_INSTANT);

        info.put("serverPort", serverPort);
        info.put("reportPath", "s3://" + reportBucket);
        info.put("backupPath", "s3://" + reportBucket + "/backups");
        info.put("generatedAt", timestamp);
        
        return info;
    }

    public String buildReportDownloadUrl(String reportName) {
        return reportsDownloadUrl + "/" + reportName;
    }
}
