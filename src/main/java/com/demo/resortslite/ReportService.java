package com.demo.resortslite;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // FIXED cr-java-0061, cr-java-0062, cr-java-0063: Replaced hard-coded file paths with GCS bucket configuration
    @Value("${gcs.reports.bucket:${GCS_REPORTS_BUCKET:resort-reports-bucket}}")
    private String reportsBucketName;

    @Value("${gcs.backup.bucket:${GCS_BACKUP_BUCKET:resort-backups-bucket}}")
    private String backupBucketName;

    // FIXED cr-java-0077: Replaced hard-coded port with environment variable configuration
    @Value("${server.port:${PORT:8080}}")
    private int serverPort;

    // FIXED cr-java-0071: Externalized report download URL to environment variable
    @Value("${app.report.download.url:${REPORT_DOWNLOAD_URL:https://reports.resorts-cloud.com}}")
    private String reportDownloadBaseUrl;

    private final Storage storage;

    public ReportService() {
        // Initialize Google Cloud Storage client
        this.storage = StorageOptions.getDefaultInstance().getService();
    }

    /**
     * Generates a monthly report and stores it in Google Cloud Storage.
     * FIXED cr-java-0061, cr-java-0062, cr-java-0063: Migrated from local file system to GCS
     * 
     * @param month The month for the report
     * @param year The year for the report
     * @return Map containing report generation status and GCS path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        
        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            outputStream.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n".getBytes(StandardCharsets.UTF_8));
            outputStream.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n".getBytes(StandardCharsets.UTF_8));
            outputStream.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n".getBytes(StandardCharsets.UTF_8));

            // Upload to Google Cloud Storage
            BlobId blobId = BlobId.of(reportsBucketName, fileName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType("text/csv")
                    .build();
            
            storage.create(blobInfo, outputStream.toByteArray());

            result.put("status", "generated");
            result.put("gcsPath", "gs://" + reportsBucketName + "/" + fileName);
            result.put("bucket", reportsBucketName);
            result.put("fileName", fileName);
            result.put("serverPort", serverPort);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using externalized configuration.
     * FIXED cr-java-0071: Replaced hard-coded URL with environment variable
     * 
     * @param reportName The name of the report to download
     * @return The complete download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // FIXED cr-java-0071: Using HTTPS URL from environment variable instead of hard-coded HTTP
        return reportDownloadBaseUrl + "/download/" + reportName;
    }

    /**
     * Returns system information with cloud-native configuration.
     * FIXED cr-java-0111: Replaced local time with UTC timestamp
     * 
     * @return Map containing system configuration information
     */
    public Map<String, Object> getSystemInfo() {
        // FIXED cr-java-0111: Using UTC timestamp instead of server-local time
        String timestamp = DateTimeFormatter.ISO_INSTANT
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        
        Map<String, Object> info = new HashMap<>();
        info.put("reportsBucket", reportsBucketName);
        info.put("backupBucket", backupBucketName);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        info.put("timezone", "UTC");
        return info;
    }
}
