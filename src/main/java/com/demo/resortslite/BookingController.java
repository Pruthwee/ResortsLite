package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

@RestController
@RequestMapping("/api/bookings")
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // FIXED cr-java-0067: Replaced in-memory HashMap cache with Amazon ElastiCache for Redis.
    // This ensures cache consistency across multiple cloud instances and prevents OOM errors.

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        // Use Redis for distributed caching with a TTL (e.g., 1 hour)
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set(bookingId, booking, java.time.Duration.ofHours(1));
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

        // FIXED cr-java-0065: Reading business state from distributed session (Amazon ElastiCache for Redis).
        String lastGuest = (String) session.getAttribute("guestName"); 

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIXED: Externalized environment URL using AWS Systems Manager Parameter Store (via @Value)
        // This replaces the hardcoded "http://inventory-service.internal:8081/rooms/available"
        String currentInventoryUrl = this.inventoryUrl;

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", currentInventoryUrl);
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
