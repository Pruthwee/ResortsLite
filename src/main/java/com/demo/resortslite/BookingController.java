package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    @Value("${ELASTICACHE_ENDPOINT:localhost}")
    private String redisEndpoint;

    private static final int CACHE_TTL_SECONDS = 3600; // 1 hour TTL

    // Lazily-initialized Redis client for distributed caching (replaces in-memory HashMap)
    private RedisClient redisClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private RedisCommands<String, String> getRedisSync() {
        if (redisClient == null) {
            try {
                redisClient = RedisClient.create("redis://" + redisEndpoint + ":6379");
            } catch (Exception e) {
                return null;
            }
        }
        StatefulRedisConnection<String, String> connection = redisClient.connect();
        return connection.sync();
    }

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Session state stored in Redis via Spring Session (replaces in-memory HttpSession)
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // Cache stored in Redis with TTL (replaces in-memory HashMap cache)
        try {
            String cacheKey = "booking:" + booking.get("bookingId");
            String serialized = objectMapper.writeValueAsString(booking);
            RedisCommands<String, String> sync = getRedisSync();
            if (sync != null) {
                sync.setex(cacheKey, CACHE_TTL_SECONDS, serialized);
            }
        } catch (Exception e) {
            // Old failure path: ignore cache errors, booking still succeeds
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

        // Session state read from Redis via Spring Session (replaces in-memory HttpSession)
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
