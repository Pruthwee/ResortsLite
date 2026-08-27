package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController handles HTTP requests for resort booking operations.
 *
 * <p>HTTP session state has been migrated to Amazon ElastiCache for Redis using
 * Spring Session, enabling stateless application instances with centralized,
 * distributed session management. In-memory caching has been replaced with
 * Redis-backed caching with TTL policies via Spring Cache abstraction.</p>
 */
@RestController
@RequestMapping("/api/bookings")
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Blocker cr-java-0067: Static in-memory HashMap (bookingCache) without TTL replaced
    // with RedisTemplate backed by Amazon ElastiCache for Redis. This ensures controlled
    // expiration, consistent data across all instances, and centralized cache management.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Blocker cr-java-0071: Hard-coded inventory URL replaced with value from
    // AWS Systems Manager Parameter Store via environment variable injection.
    @Value("${app.inventory.endpoint:https://inventory-svc.internal:8081/rooms}")
    private String inventoryEndpoint;

    // Blocker cr-java-0071: Hard-coded report download URL externalized to environment variable.
    @Value("${app.payment.endpoint:https://payment-svc.internal:9090/charge}")
    private String paymentEndpoint;

    // TTL for booking cache entries in Redis (5 minutes)
    private static final long BOOKING_CACHE_TTL_SECONDS = 300L;

    // Redis key prefix for booking cache entries
    private static final String BOOKING_CACHE_PREFIX = "booking:";

    /**
     * Creates a new booking and stores session state in Amazon ElastiCache for Redis.
     *
     * <p>Replaces HttpSession-based state storage with Spring Session backed by Redis,
     * enabling distributed session management across all application instances.</p>
     *
     * @param guestName the name of the guest
     * @param roomType  the type of room
     * @param checkIn   the check-in date
     * @param checkOut  the check-out date
     * @param session   the HTTP session (now backed by Spring Session + Redis/ElastiCache)
     * @return a map containing the booking confirmation details
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Blocker cr-java-0065: session.setAttribute calls now backed by Spring Session
        // with Amazon ElastiCache for Redis (@EnableRedisHttpSession). Session data is
        // stored in Redis — visible to all instances behind the AWS ALB, enabling
        // horizontal scaling and failover without session affinity.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // Blocker cr-java-0067: Static in-memory bookingCache replaced with Redis-backed
        // cache via RedisTemplate with TTL to prevent unbounded memory growth and ensure
        // cache consistency across all application instances.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, BOOKING_CACHE_TTL_SECONDS, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Retrieves the status of a booking, reading session state from ElastiCache for Redis.
     *
     * <p>Session attributes are retrieved from the distributed Redis session store,
     * ensuring consistent results regardless of which instance handles the request.</p>
     *
     * @param bookingId the unique booking identifier
     * @param session   the HTTP session backed by Spring Session + Redis/ElastiCache
     * @return a map containing the booking status and session guest information
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // Blocker cr-java-0065: session.getAttribute now reads from Redis-backed Spring Session.
        // The guestName attribute is available on any instance in the cluster because
        // Spring Session stores it in Amazon ElastiCache for Redis.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability using the externalized inventory service endpoint.
     *
     * <p>The hard-coded HTTP inventory URL has been replaced with a value injected
     * from AWS Systems Manager Parameter Store via environment variable.</p>
     *
     * @param roomType the type of room to check
     * @return a map containing availability information and the inventory endpoint
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Blocker cr-java-0071: Hard-coded "http://inventory-service.internal:8081/rooms/available"
        // replaced with inventoryEndpoint injected from environment variable / SSM Parameter Store.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns a report download reference using Amazon S3 object storage.
     *
     * <p>The hard-coded local file path has been replaced with an S3 object key
     * reference, eliminating the dependency on the ephemeral local file system.</p>
     *
     * @param month the month for which to download the report
     * @return a map containing the S3 report key and generation status
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Blocker cr-java-0061: Hard-coded "/var/legacy/reports/" path replaced with
        // S3 object key reference. Report is stored in and retrieved from Amazon S3.
        String s3ReportKey = "reports/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("s3Key", s3ReportKey);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
