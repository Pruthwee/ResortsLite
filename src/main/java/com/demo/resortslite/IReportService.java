package com.demo.resortslite;

import java.util.Map;

/**
 * Report service interface for loose coupling
 * Enables independent deployment and service-to-service communication
 */
public interface IReportService {
    
    Map<String, Object> generateMonthlyReport(String month, String year);
    
    String buildReportDownloadUrl(String reportName);
    
    Map<String, Object> getSystemInfo();
}
