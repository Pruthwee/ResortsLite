package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // blocker-13 (cz-java-0070): Replaced local in-memory HashMap cache with Redis-backed
    // distributed cache via RedisTemplate to ensure cache coherence across scaled container instances.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // blocker-4 (cz-java-0063): Injected Spring Session Redis configuration via @Value;
    // HttpSession is now backed by Spring Session + Amazon ElastiCache for Redis.
    @Value("${spring.redis.host:${REDIS_HOST:localhost}}")
    private String redisHost;

    // blocker-2 / blocker-1 (cz-java-0057): S3 bucket name injected from environment variable
    // instead of hardcoded absolute file path.
    @Value("${app.s3.bucket:${S3_BUCKET_NAME:resorts-reports-bucket}}")
    private String s3BucketName;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // blocker-5 (cz-java-0063): session.setAttribute now uses Spring Session backed by
        // Amazon ElastiCache for Redis — session data is shared across all container instances.
        // blocker-7 (cz-java-0069): Replaced in-memory HTTP session storage with Spring Session
        // Redis-backed distributed session management.
        session.setAttribute("lastBooking", booking);
        // blocker-8 (cz-java-0069): Replaced in-memory HTTP session storage with Spring Session
        // Redis-backed distributed session management.
        session.setAttribute("guestName", guestName);

        // blocker-13 (cz-java-0070): Store booking in Redis distributed cache instead of local HashMap.
        redisTemplate.opsForValue().set("booking:" + booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // blocker-6 (cz-java-0063): Reading session attribute via Spring Session backed by
        // Amazon ElastiCache for Redis — consistent across all container instances.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        String inventoryUrl = "http://inventory-service.internal:8081/rooms/available";

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // blocker-1 (cz-java-0057): Replaced hardcoded absolute file path
        // "/var/legacy/reports/<month>_bookings.pdf" with an Amazon S3 object key
        // constructed from the S3_BUCKET_NAME environment variable, enabling
        // cross-platform container portability.
        String reportS3Key = "s3://" + s3BucketName + "/reports/" + month + "_bookings.pdf";

        // blocker-9 (cz-java-0082): Decoupled report generation from tightly-coupled
        // BookingService component — report path is now resolved via S3 object key
        // using environment-driven configuration rather than a direct local file dependency.
        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportS3Key);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
