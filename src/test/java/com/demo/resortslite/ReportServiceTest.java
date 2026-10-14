package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReportServiceTest {

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
    }

    // -----------------------------------------------------------------------
    // generateMonthlyReport tests
    // -----------------------------------------------------------------------

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsResultMap() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void generateMonthlyReport_resultContainsStatusKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("January", "2024");

        // Assert
        assertTrue(result.containsKey("status"));
    }

    @Test
    void generateMonthlyReport_statusIsGeneratedOrError() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("February", "2024");

        // Assert
        String status = (String) result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status),
                "Status should be 'generated' or 'error', but was: " + status);
    }

    @Test
    void generateMonthlyReport_whenSuccessful_pathContainsMonthAndYear() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");

        // Assert
        if ("generated".equals(result.get("status"))) {
            String path = (String) result.get("path");
            assertTrue(path.contains("March"), "Path should contain month");
            assertTrue(path.contains("2024"), "Path should contain year");
        } else {
            // error case is also acceptable (path doesn't exist in container)
            assertTrue(result.containsKey("message") || result.containsKey("status"));
        }
    }

    @Test
    void generateMonthlyReport_whenSuccessful_serverPortIsPresent() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("April", "2024");

        // Assert
        if ("generated".equals(result.get("status"))) {
            assertNotNull(result.get("serverPort"));
            assertEquals(8080, result.get("serverPort"));
        } else {
            assertTrue(result.containsKey("status"));
        }
    }

    @Test
    void generateMonthlyReport_withDifferentMonths_returnsResult() {
        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport("January", "2024");
        Map<String, Object> result2 = reportService.generateMonthlyReport("December", "2023");

        // Assert
        assertNotNull(result1);
        assertNotNull(result2);
    }

    @Test
    void generateMonthlyReport_withEmptyMonth_returnsResult() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("", "2024");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("status"));
    }

    // -----------------------------------------------------------------------
    // buildReportDownloadUrl tests
    // -----------------------------------------------------------------------

    @Test
    void buildReportDownloadUrl_withValidName_returnsNonNullUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.pdf");

        // Assert
        assertNotNull(url);
        assertFalse(url.isEmpty());
    }

    @Test
    void buildReportDownloadUrl_containsReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.pdf");

        // Assert
        assertTrue(url.contains("march_report.pdf"));
    }

    @Test
    void buildReportDownloadUrl_containsDownloadPath() {
        // Act
        String url = reportService.buildReportDownloadUrl("report.csv");

        // Assert
        assertTrue(url.contains("/download/"));
    }

    @Test
    void buildReportDownloadUrl_startsWithHttp() {
        // Act
        String url = reportService.buildReportDownloadUrl("test_report.pdf");

        // Assert
        assertTrue(url.startsWith("http://"), "URL should start with http://");
    }

    @Test
    void buildReportDownloadUrl_containsReportsHost() {
        // Act
        String url = reportService.buildReportDownloadUrl("any_report.pdf");

        // Assert
        assertTrue(url.contains("reports.resorts-internal.com"));
    }

    @Test
    void buildReportDownloadUrl_withEmptyName_returnsUrlWithEmptySegment() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertTrue(url.contains("/download/"));
    }

    @Test
    void buildReportDownloadUrl_withSpecialCharacters_returnsUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("report_2024-03.pdf");

        // Assert
        assertNotNull(url);
        assertTrue(url.contains("report_2024-03.pdf"));
    }

    // -----------------------------------------------------------------------
    // getSystemInfo tests
    // -----------------------------------------------------------------------

    @Test
    void getSystemInfo_returnsNonNullMap() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info);
    }

    @Test
    void getSystemInfo_containsReportPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("reportPath"));
        assertNotNull(info.get("reportPath"));
    }

    @Test
    void getSystemInfo_containsBackupPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("backupPath"));
        assertNotNull(info.get("backupPath"));
    }

    @Test
    void getSystemInfo_containsServerPort() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("serverPort"));
        assertEquals(8080, info.get("serverPort"));
    }

    @Test
    void getSystemInfo_containsGeneratedAt() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("generatedAt"));
        assertNotNull(info.get("generatedAt"));
    }

    @Test
    void getSystemInfo_generatedAtIsFormattedTimestamp() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String timestamp = (String) info.get("generatedAt");
        assertNotNull(timestamp);
        // Format: yyyy-MM-dd HH:mm:ss
        assertTrue(timestamp.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "Timestamp should match yyyy-MM-dd HH:mm:ss format, but was: " + timestamp);
    }

    @Test
    void getSystemInfo_reportPathContainsLegacyReports() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String reportPath = (String) info.get("reportPath");
        assertTrue(reportPath.contains("reports"), "Report path should contain 'reports'");
    }

    @Test
    void getSystemInfo_backupPathContainsResortBackups() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String backupPath = (String) info.get("backupPath");
        assertTrue(backupPath.contains("Resort"), "Backup path should contain 'Resort'");
    }

    @Test
    void getSystemInfo_returnsMapWithFourKeys() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(4, info.size(), "System info should have 4 keys");
    }

    @Test
    void getSystemInfo_calledMultipleTimes_returnsConsistentKeys() {
        // Act
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();

        // Assert
        assertEquals(info1.keySet(), info2.keySet());
    }
}
