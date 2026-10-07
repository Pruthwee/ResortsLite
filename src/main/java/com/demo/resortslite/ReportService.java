package com.demo.resortslite;

import org.springframework.stereotype.Service;

import java.io.File;
/*
 * JAVA8_TO_25_UTF8_DEFAULT_CHARSET (Issue #5 — high):
 *   Replaced: new FileWriter(path)  — uses platform default charset
 *   With:     new FileWriter(path, StandardCharsets.UTF_8)
 *
 * Java 18 (JEP 400) changed the default charset to UTF-8 on most platforms,
 * but relying on the platform default is still a portability risk. Explicitly
 * specifying StandardCharsets.UTF_8 ensures consistent file encoding across
 * all JVM versions and operating systems, including Java 25.
 *
 * The FileWriter(String, Charset) constructor was added in Java 11, so this
 * change is fully compatible with the Java 25 target.
 */
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // VIOLATION czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute path.
    // /var/legacy/reports does not exist in a Docker container image.
    private static final String REPORT_BASE_PATH = "/var/legacy/reports/"; // czr-java-001

    // VIOLATION czr-java-001 [Software Portability / Mandatory]: Windows-style absolute path
    // will fail on any Linux-based container or cloud host.
    private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\"; // czr-java-001

    // VIOLATION czr-port-001 [Software Portability / High]: Fixed server port hardcoded in
    // application logic. Container orchestration (ECS/EKS) dynamically assigns ports.
    private static final int SERVER_PORT = 8080; // czr-port-001

    /**
     * Generates a monthly booking report CSV file.
     *
     * <p>JAVA8_TO_25_UTF8_DEFAULT_CHARSET: FileWriter is constructed with an explicit
     * {@link StandardCharsets#UTF_8} charset argument to avoid relying on the JVM
     * platform default charset (changed to UTF-8 by default in Java 18 via JEP 400,
     * but explicit specification is required for deterministic cross-platform behaviour).
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = REPORT_BASE_PATH + fileName; // czr-java-001

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(REPORT_BASE_PATH); // czr-java-001
            if (!reportDir.exists()) {
                reportDir.mkdirs();
            }

            /*
             * JAVA8_TO_25_UTF8_DEFAULT_CHARSET: Explicitly specify UTF-8 charset.
             * FileWriter(String fileName, Charset charset) — available since Java 11.
             * Replaces the no-charset constructor that relied on the platform default.
             */
            try (FileWriter writer = new FileWriter(fullPath, StandardCharsets.UTF_8)) {
                writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
                writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
                writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            }

            result.put("status", "generated");
            result.put("path", fullPath);
            result.put("serverPort", SERVER_PORT); // czr-port-001

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the download URL for a named report.
     *
     * <p>VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP URL
     * hardcoded for report download. Cloud security standards enforce HTTPS.
     */
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // cr-java-0088: should be HTTPS in a cloud-native deployment
        return "http://reports.resorts-internal.com:8080/download/" + reportName; // cr-java-0088
    }

    /**
     * Returns basic system/configuration information for diagnostics.
     */
    public Map<String, Object> getSystemInfo() { // doc-missing-001
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", REPORT_BASE_PATH);  // czr-java-001
        info.put("backupPath", BACKUP_PATH);        // czr-java-001
        info.put("serverPort", SERVER_PORT);        // czr-port-001
        info.put("generatedAt", timestamp);
        return info;
    }
}
