package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/**
 * Booking Controller with Redis-backed Session Management and Distributed Caching
 * FIXED cr-java-0065: HTTP session state now stored in Amazon ElastiCache for Redis
 * FIXED cr-java-0067: In-memory cache replaced with Amazon ElastiCache for Redis with TTL
 * 
 * Spring Session automatically intercepts HttpSession operations and stores
 * session data in Redis instead of in-memory. This enables:
 * - Stateless application instances
 * - Horizontal scaling across multiple EC2 instances
 * - Session persistence during instance termination or auto-scaling
 * - Load balancer compatibility (no sticky sessions required)
 * 
 * Spring Cache with Redis replaces the static HashMap cache with distributed caching:
 * - Shared cache across all application instances
 * - Automatic TTL-based expiration (1 hour for bookings)
 * - No memory growth issues
 * - Cache consistency across horizontal scaling
 * 
 * All HttpSession.setAttribute() and getAttribute() calls are transparently
 * backed by Redis through Spring Session Data Redis.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // FIXED cr-java-0067: Removed static HashMap cache
    // Replaced with Spring Cache abstraction backed by Amazon ElastiCache for Redis
    // Cache is now distributed, has TTL (1 hour), and is shared across all instances

    /**
     * Create a new booking and store booking information in Redis-backed session
     * FIXED cr-java-0065: Session data stored in Amazon ElastiCache for Redis
     * FIXED cr-java-0067: Booking cached in Redis with 1-hour TTL via @CachePut
     */
    @PostMapping("/create")
    @CachePut(value = "bookings", key = "#result['bookingId']")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIXED cr-java-0065: Session attributes now stored in Redis via Spring Session
        // Data is accessible across all application instances in the cluster
        // Redis connection configured via application.properties (REDIS_HOST, REDIS_PORT)
        session.setAttribute("lastBooking", booking); // Stored in Redis
        session.setAttribute("guestName", guestName); // Stored in Redis

        // FIXED cr-java-0067: @CachePut annotation stores booking in Redis cache with TTL
        // Cache key: bookingId, Cache name: "bookings" (1-hour TTL configured in RedisCacheConfig)
        // No manual cache.put() needed - Spring handles it automatically

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Get booking status and retrieve guest information from Redis-backed session
     * FIXED cr-java-0065: Session data retrieved from Amazon ElastiCache for Redis
     * FIXED cr-java-0067: Booking retrieved from Redis cache via @Cacheable
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // FIXED cr-java-0065: Session data retrieved from Redis via Spring Session
        // Works correctly across all instances - no session affinity required
        String lastGuest = (String) session.getAttribute("guestName"); // Retrieved from Redis

        // FIXED cr-java-0067: getBookingDetails() uses @Cacheable to retrieve from Redis cache
        // If cache miss, fetches from database and stores in cache with TTL
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
        // VIOLATION czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute
        // file path. This path does not exist inside a container image. Container images
        // have their own isolated file systems — /var/legacy/reports won't be present.
        String reportPath = "/var/legacy/reports/" + month + "_bookings.pdf"; // czr-java-001

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
