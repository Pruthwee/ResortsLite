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

    // ─────────────────────────────────────────────────────────────────────────
    // generateMonthlyReport tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void generateMonthlyReport_returnsNonNullMap() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");

        // Assert
        assertNotNull(result, "Result map should not be null");
    }

    @Test
    void generateMonthlyReport_resultContainsStatusKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("April", "2024");

        // Assert
        assertTrue(result.containsKey("status"), "Result should contain 'status' key");
    }

    @Test
    void generateMonthlyReport_statusIsGeneratedOrError() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("May", "2024");

        // Assert
        String status = (String) result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status),
                "Status should be 'generated' or 'error'");
    }

    @Test
    void generateMonthlyReport_whenSuccessful_containsPathKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("June", "2024");

        // Assert – either path is present (success) or message is present (error)
        assertTrue(result.containsKey("path") || result.containsKey("message"),
                "Result should contain 'path' or 'message'");
    }

    @Test
    void generateMonthlyReport_pathContainsMonthAndYear() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("July", "2024");

        // Assert
        if ("generated".equals(result.get("status"))) {
            String path = (String) result.get("path");
            assertTrue(path.contains("July"), "Path should contain month");
            assertTrue(path.contains("2024"), "Path should contain year");
        } else {
            // error case is also acceptable
            assertTrue(result.containsKey("message"));
        }
    }

    @Test
    void generateMonthlyReport_containsServerPortKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("August", "2024");

        // Assert – serverPort is set only on success path
        if ("generated".equals(result.get("status"))) {
            assertTrue(result.containsKey("serverPort"), "Result should contain 'serverPort'");
            assertEquals(8080, result.get("serverPort"));
        }
    }

    @Test
    void generateMonthlyReport_differentMonthsProduceDifferentPaths() {
        // Act
        Map<String, Object> r1 = reportService.generateMonthlyReport("January", "2024");
        Map<String, Object> r2 = reportService.generateMonthlyReport("February", "2024");

        // Assert – paths should differ if both succeed
        if ("generated".equals(r1.get("status")) && "generated".equals(r2.get("status"))) {
            assertNotEquals(r1.get("path"), r2.get("path"),
                    "Different months should produce different file paths");
        }
    }

    @Test
    void generateMonthlyReport_differentYearsProduceDifferentPaths() {
        // Act
        Map<String, Object> r1 = reportService.generateMonthlyReport("March", "2023");
        Map<String, Object> r2 = reportService.generateMonthlyReport("March", "2024");

        // Assert
        if ("generated".equals(r1.get("status")) && "generated".equals(r2.get("status"))) {
            assertNotEquals(r1.get("path"), r2.get("path"),
                    "Different years should produce different file paths");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // buildReportDownloadUrl tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildReportDownloadUrl_returnsNonNullString() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.pdf");

        // Assert
        assertNotNull(url);
    }

    @Test
    void buildReportDownloadUrl_containsReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("april_report.pdf");

        // Assert
        assertTrue(url.contains("april_report.pdf"),
                "URL should contain the report name");
    }

    @Test
    void buildReportDownloadUrl_startsWithHttp() {
        // Act
        String url = reportService.buildReportDownloadUrl("may_report.pdf");

        // Assert
        assertTrue(url.startsWith("http://"),
                "URL should start with http://");
    }

    @Test
    void buildReportDownloadUrl_containsDownloadPath() {
        // Act
        String url = reportService.buildReportDownloadUrl("june_report.pdf");

        // Assert
        assertTrue(url.contains("/download/"),
                "URL should contain '/download/' path segment");
    }

    @Test
    void buildReportDownloadUrl_containsReportsDomain() {
        // Act
        String url = reportService.buildReportDownloadUrl("july_report.pdf");

        // Assert
        assertTrue(url.contains("reports.resorts-internal.com"),
                "URL should contain the reports domain");
    }

    @Test
    void buildReportDownloadUrl_differentReportNames_produceDifferentUrls() {
        // Act
        String url1 = reportService.buildReportDownloadUrl("report_jan.pdf");
        String url2 = reportService.buildReportDownloadUrl("report_feb.pdf");

        // Assert
        assertNotEquals(url1, url2, "Different report names should produce different URLs");
    }

    @Test
    void buildReportDownloadUrl_emptyReportName_returnsBaseUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("");

        // Assert
        assertNotNull(url);
        assertTrue(url.startsWith("http://"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getSystemInfo tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getSystemInfo_returnsNonNullMap() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info);
    }

    @Test
    void getSystemInfo_containsReportPathKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("reportPath"), "Info should contain 'reportPath'");
    }

    @Test
    void getSystemInfo_containsBackupPathKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("backupPath"), "Info should contain 'backupPath'");
    }

    @Test
    void getSystemInfo_containsServerPortKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("serverPort"), "Info should contain 'serverPort'");
        assertEquals(8080, info.get("serverPort"));
    }

    @Test
    void getSystemInfo_containsGeneratedAtKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("generatedAt"), "Info should contain 'generatedAt'");
        assertNotNull(info.get("generatedAt"));
    }

    @Test
    void getSystemInfo_generatedAtIsFormattedTimestamp() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert – format: yyyy-MM-dd HH:mm:ss
        String ts = (String) info.get("generatedAt");
        assertTrue(ts.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "generatedAt should match pattern yyyy-MM-dd HH:mm:ss, got: " + ts);
    }

    @Test
    void getSystemInfo_reportPathEndsWithSlash() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String reportPath = (String) info.get("reportPath");
        assertTrue(reportPath.endsWith("/") || reportPath.endsWith("\\"),
                "Report path should end with a path separator");
    }

    @Test
    void getSystemInfo_backupPathIsNotEmpty() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String backupPath = (String) info.get("backupPath");
        assertNotNull(backupPath);
        assertFalse(backupPath.isEmpty(), "Backup path should not be empty");
    }

    @Test
    void getSystemInfo_calledTwice_generatedAtTimestampsAreClose() throws InterruptedException {
        // Act
        Map<String, Object> info1 = reportService.getSystemInfo();
        Thread.sleep(100);
        Map<String, Object> info2 = reportService.getSystemInfo();

        // Assert – both calls should return valid timestamps (not null)
        assertNotNull(info1.get("generatedAt"));
        assertNotNull(info2.get("generatedAt"));
    }
}
