package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // FIXED cr-java-0067: Replaced in-memory cache with Redis-backed distributed cache
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // FIXED cr-java-0071: Externalized inventory service URL to environment variable
    @Value("${app.inventory.endpoint:${INVENTORY_SERVICE_URL:https://inventory-service.internal:8081/rooms/available}}")
    private String inventoryServiceUrl;

    // Cache TTL configuration for Redis
    private static final long CACHE_TTL_MINUTES = 30;

    /**
     * Creates a new booking and stores state in Redis instead of HTTP session.
     * FIXED cr-java-0065: Migrated from HTTP session to Redis for distributed state management
     * FIXED cr-java-0067: Replaced in-memory cache with Redis with TTL
     * 
     * @param guestName Guest name
     * @param roomType Room type
     * @param checkIn Check-in date
     * @param checkOut Check-out date
     * @return Booking confirmation response
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);
        String bookingId = (String) booking.get("bookingId");

        // FIXED cr-java-0065: Store booking state in Redis instead of HTTP session
        // This enables stateless architecture and horizontal scaling
        redisTemplate.opsForValue().set("booking:last:" + guestName, booking, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set("booking:guest:" + bookingId, guestName, CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        // FIXED cr-java-0067: Store in Redis with TTL instead of unbounded in-memory cache
        redisTemplate.opsForValue().set("booking:cache:" + bookingId, booking, CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Retrieves booking status from Redis instead of HTTP session.
     * FIXED cr-java-0065: Migrated from HTTP session to Redis for distributed state management
     * 
     * @param bookingId The booking ID to retrieve
     * @return Booking status response
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {

        // FIXED cr-java-0065: Read from Redis instead of HTTP session
        // This works across all instances in a distributed environment
        String lastGuest = (String) redisTemplate.opsForValue().get("booking:guest:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability using externalized service URL.
     * FIXED cr-java-0071: Replaced hard-coded URL with environment variable
     * 
     * @param roomType The room type to check
     * @return Availability response
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIXED cr-java-0071: Using HTTPS URL from environment variable instead of hard-coded HTTP
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryServiceUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Downloads report from GCS instead of local file system.
     * Note: Report generation now uses GCS - no hard-coded file paths
     * 
     * @param month The month for the report
     * @return Report download response
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report generation now uses GCS - no hard-coded file paths
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        response.put("note", "Reports are now stored in Google Cloud Storage");
        return response;
    }
}
