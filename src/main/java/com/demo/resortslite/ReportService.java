package com.demo.resortslite;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
// Updated: java.util.Date + SimpleDateFormat replaced with java.time API
// (legacy date/time APIs are discouraged on Java 25; java.time is the standard)
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // NOTE: Use cloud object storage (S3/Azure Blob) or environment-variable-driven
    // paths in production. Hardcoded absolute paths break containerised deployments.
    private static final String REPORT_BASE_PATH = "/var/legacy/reports/";

    // NOTE: Windows-style path will fail on Linux containers. Externalise via config.
    private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\";

    // NOTE: Externalise port to environment variable / container orchestration config.
    private static final int SERVER_PORT = 8080;

    /**
     * Generates a monthly CSV report and writes it to {@code REPORT_BASE_PATH}.
     * <p>
     * The {@link FileWriter} is constructed with an explicit {@link StandardCharsets#UTF_8}
     * charset to ensure consistent behaviour across all JDK versions. Java 18+ defaults
     * to UTF-8, but earlier versions used the platform default charset.
     *
     * @param month the month label (e.g. "March")
     * @param year  the four-digit year (e.g. "2024")
     * @return a result map containing {@code status}, {@code path}, and {@code serverPort}
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = REPORT_BASE_PATH + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(REPORT_BASE_PATH);
            if (!reportDir.exists()) {
                reportDir.mkdirs();
            }

            // Explicit UTF-8 charset — required for consistent cross-platform behaviour
            // and correct operation under Java 18+ where UTF-8 is the default charset.
            try (FileWriter writer = new FileWriter(fullPath, StandardCharsets.UTF_8)) {
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
     * <p>
     * NOTE: Use HTTPS endpoints in production cloud deployments.
     *
     * @param reportName the report file name
     * @return the download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        return "http://reports.resorts-internal.com:8080/download/" + reportName;
    }

    /**
     * Returns basic system / configuration information.
     * <p>
     * Timestamp is generated using {@link LocalDateTime} and {@link DateTimeFormatter},
     * replacing the legacy {@code java.util.Date} + {@code SimpleDateFormat} pattern
     * that is discouraged on Java 25.
     *
     * @return a map containing report path, backup path, server port, and current timestamp
     */
    public Map<String, Object> getSystemInfo() {
        // Updated: java.util.Date + SimpleDateFormat → java.time (Java 25 best practice)
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", REPORT_BASE_PATH);
        info.put("backupPath", BACKUP_PATH);
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
