package com.demo.resortslite;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // Externalized report base path via environment variable
    private static final String REPORT_BASE_PATH = System.getenv().getOrDefault("REPORT_BASE_PATH", "/tmp/reports/");

    // Externalized backup path via environment variable
    private static final String BACKUP_PATH = System.getenv().getOrDefault("BACKUP_PATH", "/tmp/backups/");

    // Externalized server port via environment variable
    private static final int SERVER_PORT = Integer.parseInt(
            System.getenv().getOrDefault("SERVER_PORT", "8080"));

    /**
     * Generates a monthly report CSV file for the given month and year.
     *
     * @param month the month for the report
     * @param year  the year for the report
     * @return a map containing the report status, path, and server port
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

            FileWriter writer = new FileWriter(fullPath);
            writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            writer.close();

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
     *
     * @param reportName the name of the report
     * @return the HTTPS download URL for the report
     */
    public String buildReportDownloadUrl(String reportName) {
        return "https://reports.resorts-internal.com:8443/download/" + reportName;
    }

    /**
     * Returns system information including report path, backup path,
     * server port, and generation timestamp.
     *
     * @return a map containing system information
     */
    public Map<String, Object> getSystemInfo() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", REPORT_BASE_PATH);
        info.put("backupPath", BACKUP_PATH);
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
