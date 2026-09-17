package com.demo.resortslite;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for generating and managing resort booking reports.
 *
 * <p>Migration notes (Java 1.8 → Java 21 / Spring Boot 3.2.x):
 * <ul>
 *   <li>Hardcoded absolute file paths replaced with environment-variable-driven paths
 *       (REPORT_BASE_PATH, BACKUP_PATH) for container portability across ECS / EKS.</li>
 *   <li>Legacy {@code SimpleDateFormat} / {@code java.util.Date} replaced with
 *       {@code java.time.LocalDateTime} and {@code DateTimeFormatter} (thread-safe,
 *       Java 8+ date/time API, fully supported in Java 21).</li>
 *   <li>Hardcoded server port replaced with SERVER_PORT environment variable.</li>
 *   <li>Plain HTTP report download URL replaced with HTTPS; host resolved from
 *       REPORT_HOST environment variable for cloud portability.</li>
 * </ul>
 */
@Service
public class ReportService {

    /**
     * Base path for report files. Resolved from the REPORT_BASE_PATH environment variable
     * so that the application is portable across containers, ECS tasks, and local environments.
     * Falls back to /tmp/reports when the variable is not set.
     *
     * <p>Updated: replaces the hardcoded absolute path that broke container deployments.</p>
     */
    private static final String REPORT_BASE_PATH =
            System.getenv().getOrDefault("REPORT_BASE_PATH", "/tmp/reports");

    /**
     * Backup path resolved from the BACKUP_PATH environment variable.
     * Avoids hardcoded OS-specific paths that break on Linux-based containers.
     */
    private static final String BACKUP_PATH =
            System.getenv().getOrDefault("BACKUP_PATH", "/tmp/resort-backups/nightly");

    /**
     * Server port resolved from the SERVER_PORT environment variable so that
     * container orchestration platforms (ECS / EKS) can assign ports dynamically.
     */
    private static final int SERVER_PORT =
            Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "8080"));

    /**
     * Generates a monthly booking report CSV and writes it to the configured report directory.
     *
     * <p>Updated: uses {@code java.time.LocalDateTime} and {@code DateTimeFormatter}
     * instead of the legacy {@code SimpleDateFormat} / {@code java.util.Date} APIs.</p>
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
     * Builds a secure HTTPS download URL for the given report name.
     *
     * <p>Updated: HTTPS enforced; host resolved from REPORT_HOST environment variable
     * to avoid hardcoded hostnames that break in cloud environments.</p>
     *
     * @param reportName the name of the report file
     * @return the fully-qualified HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // Updated: HTTPS enforced; host resolved from environment variable for cloud portability.
        String reportHost = System.getenv().getOrDefault(
                "REPORT_HOST", "reports.resorts-internal.com");
        return "https://" + reportHost + "/download/" + reportName;
    }

    /**
     * Returns system information including configured paths and the current timestamp.
     *
     * <p>Updated: uses {@code java.time.LocalDateTime} with {@code DateTimeFormatter}
     * instead of the legacy {@code SimpleDateFormat} / {@code java.util.Date} APIs.
     * {@code DateTimeFormatter} is immutable and thread-safe, unlike {@code SimpleDateFormat}.</p>
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // Updated: java.time.LocalDateTime + DateTimeFormatter replaces legacy
        // SimpleDateFormat / java.util.Date (thread-safe, Java 8+ API, Java 21 compatible).
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
