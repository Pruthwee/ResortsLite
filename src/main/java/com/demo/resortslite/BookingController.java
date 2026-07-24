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

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${inventory.service.url:https://inventory-service.internal:8443/rooms/available}")
    private String inventoryUrl;

    // FIXED cr-java-0067: Replaced in-memory HashMap with Amazon ElastiCache for Redis
    // to ensure controlled expiration (TTL) and consistency across cloud instances.
    private static final String CACHE_KEY_PREFIX = "booking:";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);
        
        // Cache the booking in Redis with a TTL (e.g., 1 hour) to prevent memory growth
        String bookingId = (String) booking.get("id");
        if (bookingId != null) {
            redisTemplate.opsForValue().set(CACHE_KEY_PREFIX + bookingId, booking, 1, TimeUnit.HOURS);
        }

        // FIXED cr-java-0065: Session state now managed by Amazon ElastiCache for Redis via Spring Session
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {
        
        Map<String, Object> result = new HashMap<>();
        
        // Try to get from Redis cache first
        Map<String, Object> cachedBooking = (Map<String, Object>) redisTemplate.opsForValue().get(CACHE_KEY_PREFIX + bookingId);
        if (cachedBooking != null) {
            result.put("details", cachedBooking);
            result.put("source", "cache");
        } else {
            Map<String, Object> booking = bookingService.getBookingById(bookingId);
            result.put("details", booking);
            result.put("source", "database");
            // Cache it for next time
            if (booking != null) {
                redisTemplate.opsForValue().set(CACHE_KEY_PREFIX + bookingId, booking, 1, TimeUnit.HOURS);
            }
        }

        // FIXED cr-java-0065: Session state now managed by Amazon ElastiCache for Redis via Spring Session
        String lastGuest = (String) session.getAttribute("guestName");
        result.put("sessionGuest", lastGuest);
        
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Fixed: Externalized URL using AWS Systems Manager Parameter Store (via @Value)
        // cr-java-0071
        
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // VIOLATION czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute
        // file path. This path does not exist inside a container image.
        String reportPath = "/var/legacy/reports/" + month + "_bookings.pdf"; // czr-java-001

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
