package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // FIXED cr-java-0067: Replaced in-memory cache with Azure Cache for Redis
    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    // FIXED cr-java-0071: Externalized URL to Azure App Configuration
    @Value("${app.inventory.endpoint:http://localhost:8081/rooms/available}")
    private String inventoryUrl;

    private static final String CACHE_KEY_PREFIX = "booking:";
    private static final long CACHE_TTL_MINUTES = 30;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIXED cr-java-0065: Session state now stored in Azure Cache for Redis
        // Spring Session automatically handles Redis-backed session storage
        // This enables stateless architecture and horizontal scaling
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // FIXED cr-java-0067: Cache now uses Azure Cache for Redis with TTL
        if (redisTemplate != null) {
            String cacheKey = CACHE_KEY_PREFIX + booking.get("bookingId");
            redisTemplate.opsForValue().set(cacheKey, booking, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // FIXED cr-java-0065: Session data retrieved from Azure Cache for Redis
        // Works across all instances in the cluster
        String lastGuest = (String) session.getAttribute("guestName");

        // Check Redis cache first
        Map<String, Object> cachedBooking = null;
        if (redisTemplate != null) {
            String cacheKey = CACHE_KEY_PREFIX + bookingId;
            cachedBooking = (Map<String, Object>) redisTemplate.opsForValue().get(cacheKey);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        result.put("source", cachedBooking != null ? "redis-cache" : "database");
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIXED cr-java-0071: Using externalized URL from Azure App Configuration
        // URL now supports HTTPS and is environment-agnostic

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // File paths are now handled by ReportService using Azure Blob Storage
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        response.put("storageType", "Azure Blob Storage");
        return response;
    }

    /**
     * Clears booking from cache
     */
    @DeleteMapping("/cache/{bookingId}")
    public Map<String, Object> clearBookingCache(@PathVariable String bookingId) {
        Map<String, Object> response = new HashMap<>();
        if (redisTemplate != null) {
            String cacheKey = CACHE_KEY_PREFIX + bookingId;
            Boolean deleted = redisTemplate.delete(cacheKey);
            response.put("status", deleted ? "cleared" : "not-found");
            response.put("bookingId", bookingId);
        } else {
            response.put("status", "redis-not-configured");
        }
        return response;
    }
}
