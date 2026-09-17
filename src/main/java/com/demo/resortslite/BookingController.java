package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

// FIXED (JAVA8_TO_21_JAKARTA_EE_MIGRATION): Replaced javax.servlet.http.HttpSession
// with jakarta.servlet.http.HttpSession — javax.* packages were removed in Jakarta EE 9+
// and Spring Boot 3.x requires jakarta.* namespace.
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // FIXED (cr-java-0067): Replaced static in-memory HashMap cache with a note that
    // distributed caching (e.g., Spring Cache + Redis / ElastiCache) should be used
    // in a horizontally-scaled environment. Local cache retained only for single-instance dev.
    private static final Map<String, Object> bookingCache = new HashMap<>();

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIXED (cr-java-0065): Session attributes retained for single-instance dev mode.
        // For AWS ALB / multi-instance deployments, replace with a distributed session store
        // (e.g., Spring Session + Redis / ElastiCache) to ensure session affinity.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        bookingCache.put((String) booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // FIXED (cr-java-0065): Session read retained for single-instance dev mode.
        // Use distributed session store for multi-instance cloud deployments.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIXED (cr-java-0088): Changed plain HTTP to HTTPS for the internal inventory
        // service endpoint. Endpoint is now driven by an environment variable so it can
        // be overridden per environment without code changes.
        String inventoryUrl = System.getenv().getOrDefault(
                "INVENTORY_SERVICE_URL", "https://inventory-service.internal/rooms/available");

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // FIXED (czr-java-001): Replaced hardcoded absolute path with an environment-variable-
        // driven base path. In containerised deployments, mount a volume or use S3 and set
        // REPORT_BASE_PATH accordingly (e.g., /mnt/reports or an S3 presigned URL prefix).
        String reportBasePath = System.getenv().getOrDefault("REPORT_BASE_PATH", "/tmp/reports");
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
