package com.demo.resortslite;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
// Updated: using java.time API instead of legacy java.util.Date / SimpleDateFormat
// (JAVA8_TO_21_DATE_TIME_CHANGES — java.util.Date and SimpleDateFormat are legacy;
//  java.time.LocalDateTime + DateTimeFormatter are the modern replacements)
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // VIOLATION czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute path.
    // /var/legacy/reports does not exist in a Docker container image. Breaks containerisation.
    // Must use volume mounts, cloud object storage (S3 / Azure Blob), or environment variable.
    private static final String REPORT_BASE_PATH = "/var/legacy/reports/"; // czr-java-001

    // VIOLATION czr-java-001 [Software Portability / Mandatory]: Windows-style absolute path
    // will fail on any Linux-based container or cloud host. Hard dependency on OS path structure.
    private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\"; // czr-java-001

    // VIOLATION czr-port-001 [Software Portability / High]: Fixed server port hardcoded in
    // application logic. Container orchestration (ECS / EKS) dynamically assigns ports.
    // Hardcoded ports prevent dynamic port binding required for modern container deployment.
    private static final int SERVER_PORT = 8080; // czr-port-001

    /**
     * Generates a monthly CSV report for the given month and year.
     * Uses try-with-resources to ensure FileWriter is always closed properly.
     * (JAVA8_TO_21_DATE_TIME_CHANGES — resource management best practice)
     *
     * @param month the month (e.g. "03")
     * @param year  the year  (e.g. "2024")
     * @return a map containing the generation status and output path
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

            // Updated: using try-with-resources for proper resource management
            // (JAVA8_TO_21_DATE_TIME_CHANGES — FileWriter not closed via try-with-resources
            //  was flagged as a deprecated-api / resource-leak issue)
            try (FileWriter writer = new FileWriter(fullPath)) {
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
     * VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP URL hardcoded
     * for report download. Cloud security standards enforce HTTPS.
     *
     * @param reportName the report file name
     * @return the (currently insecure) download URL
     */
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // cr-java-0088: should be https:// in a cloud-native deployment
        return "http://reports.resorts-internal.com:8080/download/" + reportName; // cr-java-0088
    }

    /**
     * Returns basic system / configuration information.
     * Uses java.time.LocalDateTime (modern API) instead of legacy java.util.Date.
     * (JAVA8_TO_21_DATE_TIME_CHANGES)
     *
     * @return map of system info key-value pairs
     */
    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // Updated: using java.time.LocalDateTime instead of deprecated java.util.Date /
        // SimpleDateFormat (JAVA8_TO_21_DATE_TIME_CHANGES)
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        Map<String, Object> info = new HashMap<>();
        info.put("reportPath",  REPORT_BASE_PATH); // czr-java-001
        info.put("backupPath",  BACKUP_PATH);       // czr-java-001
        info.put("serverPort",  SERVER_PORT);       // czr-port-001
        info.put("generatedAt", timestamp);
        return info;
    }
}
