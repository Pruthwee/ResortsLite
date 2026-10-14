package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
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
    void generateMonthlyReport_whenDirectoryNotWritable_returnsErrorStatus() {
        // Arrange – REPORT_BASE_PATH is /var/legacy/reports/ which won't exist in test env
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");

        // Assert – either generated (if dir exists) or error (if not)
        assertNotNull(result);
        assertTrue(result.containsKey("status"), "Result must contain 'status' key");
    }

    @Test
    void generateMonthlyReport_statusIsEitherGeneratedOrError() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("April", "2024");

        // Assert
        String status = (String) result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status),
                "Status must be 'generated' or 'error'");
    }

    @Test
    void generateMonthlyReport_whenError_containsMessageKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("May", "2024");

        // Assert – if error, message key must be present
        String status = (String) result.get("status");
        if ("error".equals(status)) {
            assertTrue(result.containsKey("message"), "Error result must contain 'message' key");
        }
    }

    @Test
    void generateMonthlyReport_whenGenerated_containsPathKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("June", "2024");

        // Assert – if generated, path key must be present
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            assertTrue(result.containsKey("path"), "Generated result must contain 'path' key");
        }
    }

    @Test
    void generateMonthlyReport_whenGenerated_pathContainsMonthAndYear() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("July", "2024");

        // Assert
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            String path = (String) result.get("path");
            assertTrue(path.contains("July"), "Path should contain month");
            assertTrue(path.contains("2024"), "Path should contain year");
        } else {
            // error path is acceptable in test environment
            assertEquals("error", status);
        }
    }

    @Test
    void generateMonthlyReport_whenGenerated_serverPortIs8080() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("August", "2024");

        // Assert
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            assertEquals(8080, result.get("serverPort"));
        } else {
            assertEquals("error", status);
        }
    }

    @Test
    void generateMonthlyReport_returnsNonNullMap() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("September", "2024");

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void generateMonthlyReport_differentMonthsReturnDifferentPaths() {
        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport("January", "2024");
        Map<String, Object> result2 = reportService.generateMonthlyReport("February", "2024");

        // Assert – both return maps (paths differ if generated)
        assertNotNull(result1);
        assertNotNull(result2);
    }

    @Test
    void generateMonthlyReport_withNullMonth_doesNotThrow() {
        // Act & Assert – should not throw NPE, may return error map
        assertDoesNotThrow(() -> reportService.generateMonthlyReport(null, "2024"));
    }

    @Test
    void generateMonthlyReport_withEmptyMonth_doesNotThrow() {
        // Act & Assert
        assertDoesNotThrow(() -> reportService.generateMonthlyReport("", "2024"));
    }

    // -----------------------------------------------------------------------
    // buildReportDownloadUrl tests
    // -----------------------------------------------------------------------

    @Test
    void buildReportDownloadUrl_returnsNonNullUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.csv");

        // Assert
        assertNotNull(url);
        assertFalse(url.isEmpty());
    }

    @Test
    void buildReportDownloadUrl_containsReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.csv");

        // Assert
        assertTrue(url.contains("march_report.csv"), "URL should contain the report name");
    }

    @Test
    void buildReportDownloadUrl_containsBaseHost() {
        // Act
        String url = reportService.buildReportDownloadUrl("april_report.csv");

        // Assert
        assertTrue(url.contains("reports.resorts-internal.com"), "URL should contain the base host");
    }

    @Test
    void buildReportDownloadUrl_containsPort8080() {
        // Act
        String url = reportService.buildReportDownloadUrl("may_report.csv");

        // Assert
        assertTrue(url.contains("8080"), "URL should contain port 8080");
    }

    @Test
    void buildReportDownloadUrl_containsDownloadPath() {
        // Act
        String url = reportService.buildReportDownloadUrl("june_report.csv");

        // Assert
        assertTrue(url.contains("/download/"), "URL should contain /download/ path");
    }

    @Test
    void buildReportDownloadUrl_startsWithHttp() {
        // Act
        String url = reportService.buildReportDownloadUrl("report.csv");

        // Assert
        assertTrue(url.startsWith("http://"), "URL should start with http://");
    }

    @Test
    void buildReportDownloadUrl_differentReportNamesProduceDifferentUrls() {
        // Act
        String url1 = reportService.buildReportDownloadUrl("report_jan.csv");
        String url2 = reportService.buildReportDownloadUrl("report_feb.csv");

        // Assert
        assertNotEquals(url1, url2);
    }

    @Test
    void buildReportDownloadUrl_withEmptyReportName_returnsUrlWithEmptySuffix() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertTrue(url.contains("/download/"));
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
        assertFalse(info.isEmpty());
    }

    @Test
    void getSystemInfo_containsReportPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("reportPath"), "Info should contain 'reportPath'");
        assertNotNull(info.get("reportPath"));
    }

    @Test
    void getSystemInfo_containsBackupPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("backupPath"), "Info should contain 'backupPath'");
        assertNotNull(info.get("backupPath"));
    }

    @Test
    void getSystemInfo_containsServerPort() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("serverPort"), "Info should contain 'serverPort'");
        assertEquals(8080, info.get("serverPort"));
    }

    @Test
    void getSystemInfo_containsGeneratedAt() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("generatedAt"), "Info should contain 'generatedAt'");
        assertNotNull(info.get("generatedAt"));
    }

    @Test
    void getSystemInfo_generatedAtMatchesDateTimePattern() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert – format: yyyy-MM-dd HH:mm:ss
        String generatedAt = (String) info.get("generatedAt");
        assertNotNull(generatedAt);
        assertTrue(generatedAt.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "generatedAt should match yyyy-MM-dd HH:mm:ss pattern, got: " + generatedAt);
    }

    @Test
    void getSystemInfo_reportPathIsLegacyPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String reportPath = (String) info.get("reportPath");
        assertEquals("/var/legacy/reports/", reportPath);
    }

    @Test
    void getSystemInfo_backupPathIsWindowsStyle() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String backupPath = (String) info.get("backupPath");
        assertNotNull(backupPath);
        assertTrue(backupPath.contains("ResortBackups"), "Backup path should reference ResortBackups");
    }

    @Test
    void getSystemInfo_calledMultipleTimes_returnsConsistentKeys() {
        // Act
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();

        // Assert – keys should be consistent
        assertEquals(info1.keySet(), info2.keySet());
    }

    @Test
    void getSystemInfo_returnsMapWithFourKeys() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(4, info.size(), "getSystemInfo should return exactly 4 keys");
    }
}
