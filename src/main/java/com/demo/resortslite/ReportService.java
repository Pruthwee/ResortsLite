package com.demo.resortslite;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
// FIXED (JAVA8_TO_21_DATE_TIME_CHANGES): Replaced java.util.Date and java.text.SimpleDateFormat
// with java.time.LocalDateTime and DateTimeFormatter — legacy date/time APIs are discouraged
// in Java 21; java.time API is thread-safe and more expressive.
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // FIXED (czr-java-001): Replaced hardcoded absolute path /var/legacy/reports with an
    // environment-variable-driven base path. Set REPORT_BASE_PATH to a volume mount path,
    // an S3-backed FUSE mount, or a writable temp directory in containerised deployments.
    private static final String REPORT_BASE_PATH =
            System.getenv().getOrDefault("REPORT_BASE_PATH", "/tmp/reports");

    // FIXED (czr-java-001): Replaced Windows-style hardcoded backup path with an
    // environment-variable-driven path compatible with Linux containers and cloud hosts.
    private static final String BACKUP_PATH =
            System.getenv().getOrDefault("BACKUP_PATH", "/tmp/backups/nightly");

    // FIXED (czr-port-001): Replaced hardcoded port constant with an environment-variable-
    // driven value. Container orchestration (ECS / EKS) assigns ports dynamically; the
    // application should read server.port from application.properties or SERVER_PORT env var.
    private static final int SERVER_PORT =
            Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "8080"));

    /**
     * Generates a monthly booking report CSV and writes it to the configured report directory.
     *
     * @param month the month identifier (e.g., "2024-03")
     * @param year  the four-digit year (e.g., "2024")
     * @return a map containing the generation status and output file path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = REPORT_BASE_PATH + "/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(REPORT_BASE_PATH);
            if (!reportDir.exists()) {
                reportDir.mkdirs();
            }

            // FIXED (java-resource-001): Use try-with-resources to ensure FileWriter is
            // always closed, even if an exception occurs during write operations.
            try (FileWriter writer = new FileWriter(fullPath)) {
                writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
                writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
                writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            }

            result.put("status", "generated");
            result.put("path", fullPath);
            result.put("serverPort", SERVER_PORT);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for the given report name.
     * The base URL is driven by the REPORT_DOWNLOAD_BASE_URL environment variable so it
     * can be set to an HTTPS endpoint (e.g., CloudFront or API Gateway) per environment.
     *
     * @param reportName the name of the report file
     * @return the full HTTPS download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // FIXED (cr-java-0088): Replaced hardcoded plain-HTTP URL with an environment-variable-
        // driven base URL defaulting to HTTPS. Set REPORT_DOWNLOAD_BASE_URL in each environment.
        String baseUrl = System.getenv().getOrDefault(
                "REPORT_DOWNLOAD_BASE_URL", "https://reports.resorts-internal.com/download");
        return baseUrl + "/" + reportName;
    }

    /**
     * Returns system information including configured paths and the current timestamp.
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // FIXED (JAVA8_TO_21_DATE_TIME_CHANGES): Replaced new SimpleDateFormat(...).format(new Date())
        // with java.time.LocalDateTime.now() and DateTimeFormatter — thread-safe and idiomatic Java 21.
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", REPORT_BASE_PATH);
        info.put("backupPath", BACKUP_PATH);
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
