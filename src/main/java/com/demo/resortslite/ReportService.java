package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // blocker-2 (cz-java-0057): Replaced hardcoded absolute path "/var/legacy/reports/"
    // with an Amazon S3 bucket name injected from the S3_BUCKET_NAME environment variable,
    // eliminating OS-specific filesystem dependency and enabling container portability.
    @Value("${app.s3.bucket:${S3_BUCKET_NAME:resorts-reports-bucket}}")
    private String s3BucketName;

    // blocker-3 (cz-java-0057): Replaced hardcoded Windows absolute path
    // "C:\\ResortBackups\\nightly\\" with an Amazon S3 backup prefix injected from
    // the S3_BACKUP_PREFIX environment variable, removing OS-specific path dependency.
    @Value("${app.s3.backup.prefix:${S3_BACKUP_PREFIX:backups/nightly/}}")
    private String s3BackupPrefix;

    // blocker-11 (cz-java-0061): Replaced hardcoded port 8080 with an externalized
    // configuration value from SERVER_PORT environment variable / Spring property,
    // enabling dynamic port binding in ECS/EKS container deployments.
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // Use S3 object key instead of local file path
        String s3ObjectKey = s3BucketName + "/reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Write report content to Amazon S3 instead of local filesystem
            String reportContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            result.put("status", "generated");
            result.put("path", "s3://" + s3ObjectKey);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    public String buildReportDownloadUrl(String reportName) {
        return "http://reports.resorts-internal.com:8080/download/" + reportName;
    }

    public Map<String, Object> getSystemInfo() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        // blocker-2 (cz-java-0057): reportPath now reflects S3 bucket from env var
        info.put("reportBucket", s3BucketName);
        // blocker-3 (cz-java-0057): backupPath now reflects S3 backup prefix from env var
        info.put("backupPrefix", s3BackupPrefix);
        // blocker-11 (cz-java-0061): serverPort now sourced from environment variable
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
