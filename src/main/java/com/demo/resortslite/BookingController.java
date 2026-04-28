package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private IBookingService bookingService;

    @Autowired
    private RedisCacheService redisCacheService;

    @Autowired
    private S3StorageService s3StorageService;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIXED: blocker-7, blocker-8 (cz-java-0069) - Migrated to Spring Session with Redis
        // Session data is now stored in Redis and shared across all container instances
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // FIXED: blocker-13 (cz-java-0070) - Replaced local cache with Redis distributed cache
        // Cache is now shared across horizontally scaled container instances with TTL
        redisCacheService.put("booking:" + booking.get("bookingId"), booking, 60);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // FIXED: blocker-4, blocker-5, blocker-6 (cz-java-0063) - Using Spring Session with Redis
        // Session data persists across container restarts and horizontal scaling
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP call to
        // internal inventory service. AWS ALB, WAF, and Well-Architected security review
        // enforce HTTPS. This call will be blocked or flagged in a cloud-native setup.
        String inventoryUrl = "http://inventory-service.internal:8081/rooms/available"; // cr-java-0088

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // FIXED: blocker-1 (cz-java-0057) - Replaced absolute file path with S3 object storage
        // Files are now stored in Amazon S3 for cross-platform compatibility
        String s3Key = "reports/" + month + "_bookings.pdf";
        String reportPath = s3StorageService.getFileUrl(s3Key);

        // FIXED: blocker-9 (cz-java-0082) - Using service interface for loose coupling
        // Service communication now supports independent deployment and microservices architecture
        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
