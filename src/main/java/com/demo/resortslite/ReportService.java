package com.demo.resortslite;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // FIXED cr-java-0061, cr-java-0062, cr-java-0063: Replaced hard-coded file paths with Azure Blob Storage
    @Value("${azure.storage.account-name:}")
    private String storageAccountName;

    @Value("${azure.storage.account-key:}")
    private String storageAccountKey;

    @Value("${azure.storage.blob-endpoint:}")
    private String blobEndpoint;

    @Value("${azure.storage.container-name:reports}")
    private String containerName;

    // FIXED cr-java-0077: Replaced hard-coded port with environment variable
    @Value("${server.port:8080}")
    private int serverPort;

    // FIXED cr-java-0071: Externalized URL to Azure App Configuration
    @Value("${app.report.download.url:https://reports.resorts.azure.com}")
    private String reportDownloadBaseUrl;

    @Value("${azure.servicebus.connection-string:}")
    private String serviceBusConnectionString;

    @Value("${azure.servicebus.queue-name:scheduled-tasks}")
    private String serviceBusQueueName;

    /**
     * Generates monthly report and stores it in Azure Blob Storage
     * FIXED cr-java-0061, cr-java-0062, cr-java-0063: Migrated from local file system to Azure Blob Storage
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // Create CSV content in memory
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            outputStream.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n".getBytes(StandardCharsets.UTF_8));
            outputStream.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n".getBytes(StandardCharsets.UTF_8));
            outputStream.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n".getBytes(StandardCharsets.UTF_8));

            // Upload to Azure Blob Storage
            if (storageAccountName != null && !storageAccountName.isEmpty()) {
                BlobServiceClient blobServiceClient = createBlobServiceClient();
                BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);

                // Create container if it doesn't exist
                if (!containerClient.exists()) {
                    containerClient.create();
                }

                BlobClient blobClient = containerClient.getBlobClient(fileName);
                byte[] data = outputStream.toByteArray();
                blobClient.upload(new ByteArrayInputStream(data), data.length, true);

                result.put("status", "generated");
                result.put("storageType", "Azure Blob Storage");
                result.put("container", containerName);
                result.put("blobName", fileName);
                result.put("blobUrl", blobClient.getBlobUrl());
                result.put("serverPort", serverPort);
            } else {
                result.put("status", "error");
                result.put("message", "Azure Blob Storage not configured. Set AZURE_STORAGE_ACCOUNT_NAME and AZURE_STORAGE_ACCOUNT_KEY environment variables.");
            }

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds report download URL using externalized configuration
     * FIXED cr-java-0071: Replaced hard-coded URL with Azure App Configuration
     */
    public String buildReportDownloadUrl(String reportName) {
        // FIXED cr-java-0071: Using externalized base URL from Azure App Configuration
        return reportDownloadBaseUrl + "/download/" + reportName;
    }

    /**
     * Gets system information with cloud-native configuration
     * FIXED cr-java-0061, cr-java-0077: Replaced hard-coded paths and ports with environment variables
     */
    public Map<String, Object> getSystemInfo() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        info.put("storageType", "Azure Blob Storage");
        info.put("storageAccount", storageAccountName);
        info.put("containerName", containerName);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        info.put("reportDownloadBaseUrl", reportDownloadBaseUrl);
        return info;
    }

    /**
     * Schedules a task using Azure Service Bus scheduled messages
     * FIXED cr-java-0111: Replaced java.util.Timer with Azure Service Bus scheduled messages
     */
    public void scheduleReportGeneration(String reportType, Instant scheduledTime) {
        if (serviceBusConnectionString == null || serviceBusConnectionString.isEmpty()) {
            throw new IllegalStateException("Azure Service Bus not configured. Set AZURE_SERVICEBUS_CONNECTION_STRING environment variable.");
        }

        try {
            ServiceBusSenderClient senderClient = new ServiceBusClientBuilder()
                    .connectionString(serviceBusConnectionString)
                    .sender()
                    .queueName(serviceBusQueueName)
                    .buildClient();

            ServiceBusMessage message = new ServiceBusMessage("Generate report: " + reportType);
            message.setScheduledEnqueueTime(scheduledTime.atOffset(java.time.ZoneOffset.UTC));

            senderClient.sendMessage(message);
            senderClient.close();
        } catch (Exception e) {
            throw new RuntimeException("Failed to schedule report generation via Azure Service Bus", e);
        }
    }

    /**
     * Creates Azure Blob Service Client
     */
    private BlobServiceClient createBlobServiceClient() {
        if (blobEndpoint != null && !blobEndpoint.isEmpty()) {
            return new BlobServiceClientBuilder()
                    .endpoint(blobEndpoint)
                    .buildClient();
        } else {
            String connectionString = String.format(
                    "DefaultEndpointsProtocol=https;AccountName=%s;AccountKey=%s;EndpointSuffix=core.windows.net",
                    storageAccountName, storageAccountKey
            );
            return new BlobServiceClientBuilder()
                    .connectionString(connectionString)
                    .buildClient();
        }
    }
}
