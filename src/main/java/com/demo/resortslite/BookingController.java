package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

// Updated: javax.servlet.http.HttpSession → jakarta.servlet.http.HttpSession
// Spring Boot 3.x / Jakarta EE 10 uses the jakarta.* namespace exclusively.
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/**
 * REST controller exposing booking-related endpoints.
 *
 * <p>Migration notes (Java 1.8 → Java 21 / Spring Boot 3.2.x):
 * <ul>
 *   <li>javax.servlet.http.HttpSession replaced with jakarta.servlet.http.HttpSession
 *       (Jakarta EE 10 namespace migration).</li>
 *   <li>Plain HTTP inventory URL replaced with HTTPS for cloud-native security compliance.</li>
 *   <li>Hardcoded report path replaced with environment-variable-driven path for
 *       container portability (AWS ECS / EKS).</li>
 *   <li>Session state note added: for multi-instance ALB deployments, externalise
 *       session storage to Amazon ElastiCache (Redis) via Spring Session.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * In-memory booking cache retained for single-instance use.
     * For horizontal scaling on AWS, replace with a distributed cache
     * (e.g., Amazon ElastiCache / Redis via Spring Cache abstraction).
     */
    private static final Map<String, Object> bookingCache = new HashMap<>();

    /**
     * Creates a new booking and stores it in the session and local cache.
     *
     * @param guestName the guest's full name
     * @param roomType  the requested room category
     * @param checkIn   the check-in date (ISO-8601 string)
     * @param checkOut  the check-out date (ISO-8601 string)
     * @param session   the current HTTP session
     * @return a confirmation map containing status and booking details
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // NOTE: Session state is stored per-instance.
        // For AWS ALB multi-instance deployments, externalise session storage to
        // Amazon ElastiCache (Redis) or use Spring Session with the Redis store.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        bookingCache.put((String) booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the status of an existing booking, enriched with session context.
     *
     * @param bookingId the booking identifier
     * @param session   the current HTTP session
     * @return a map containing booking details and the session guest name
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability for the requested room type.
     *
     * <p>Updated: inventory service URL uses HTTPS for cloud-native security compliance
     * (AWS ALB / WAF). The endpoint is resolved from the INVENTORY_API_URL environment
     * variable for container portability.</p>
     *
     * @param roomType the room category to check
     * @return a map containing availability status and the inventory endpoint reference
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Updated: HTTPS endpoint for cloud-native security compliance (AWS ALB / WAF).
        // Resolved from environment variable for container portability.
        String inventoryUrl = System.getenv().getOrDefault(
                "INVENTORY_API_URL", "https://inventory-service.internal:8081/rooms/available");

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns the path of the generated monthly booking report.
     *
     * <p>Updated: report base path is resolved from the REPORT_BASE_PATH environment
     * variable (defaults to /tmp/reports) for container portability across ECS tasks
     * and local environments.</p>
     *
     * @param month the month identifier (e.g., "2024-03")
     * @return a map containing the report path and generation status message
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Updated: report path resolved from environment variable REPORT_BASE_PATH
        // (defaults to /tmp/reports) for container portability.
        String baseReportPath = System.getenv().getOrDefault("REPORT_BASE_PATH", "/tmp/reports");
        String reportPath = baseReportPath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
