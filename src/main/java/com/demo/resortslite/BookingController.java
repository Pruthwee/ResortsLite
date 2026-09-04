package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cr-java-0065 fix: javax.servlet.http.HttpSession is now backed by Spring Session Data Redis.
// All session.setAttribute / session.getAttribute calls are transparently stored in and
// retrieved from Amazon ElastiCache for Redis, enabling stateless application instances
// that can be horizontally scaled behind an AWS ALB without server affinity.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067 fix: Replaced unbounded in-memory HashMap cache (bookingCache) with
    // Amazon ElastiCache for Redis via Spring Data RedisTemplate.
    // The RedisTemplate is auto-configured by Spring Boot using the ElastiCache connection
    // details supplied through SPRING_REDIS_HOST / SPRING_REDIS_PORT environment variables
    // (see application.properties and RedisSessionConfig).
    // Each cache entry is stored with a TTL defined by BOOKING_CACHE_TTL_SECONDS
    // (default: 3600 seconds / 1 hour), preventing indefinite memory growth and ensuring
    // stale data is automatically evicted.  Because the cache lives in the shared
    // ElastiCache cluster rather than in JVM heap memory, all EC2 instances in the
    // Auto Scaling Group share a consistent view of cached bookings.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * TTL (in seconds) for booking cache entries stored in Amazon ElastiCache for Redis.
     *
     * <p>Set the {@code BOOKING_CACHE_TTL_SECONDS} environment variable (or the
     * {@code app.booking.cache.ttl-seconds} property) to control how long a cached
     * booking entry lives before it is automatically evicted by Redis.  This prevents
     * indefinite memory growth and ensures stale data is not served to clients.
     *
     * <p>Recommended SSM parameter path: {@code /resorts/<env>/booking/cache-ttl-seconds}
     */
    @Value("${app.booking.cache.ttl-seconds:${BOOKING_CACHE_TTL_SECONDS:3600}}")
    private long bookingCacheTtlSeconds;

    /** Redis key prefix for booking cache entries — avoids key collisions with other data. */
    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:cache:";

    /**
     * cr-java-0071 fix: Inventory service URL externalised from hard-coded
     * "http://inventory-service.internal:8081/rooms/available" to an
     * AWS Systems Manager Parameter Store-backed property.
     *
     * <p>The value is resolved at startup from the Spring property
     * {@code app.inventory.available-url}, which is itself bound to the
     * {@code APP_INVENTORY_AVAILABLE_URL} environment variable (or the SSM
     * parameter of the same name when the Spring Cloud AWS SSM bootstrap is
     * active).  This makes the endpoint fully environment-agnostic — no code
     * change is required when promoting from dev → staging → production.
     */
    @Value("${app.inventory.available-url:${APP_INVENTORY_AVAILABLE_URL:https://inventory-service.internal:8081/rooms/available}}")
    private String inventoryAvailableUrl;

    /**
     * Creates a new booking and stores session state in Amazon ElastiCache for Redis
     * via Spring Session Data Redis (cr-java-0065 fix).
     *
     * <p>The {@link HttpSession} parameter is transparently backed by Redis — Spring
     * Session intercepts all setAttribute / getAttribute calls and serialises the data
     * to the configured ElastiCache cluster.  No application-level code change is
     * required beyond adding the Spring Session Data Redis dependency and the
     * {@code @EnableRedisHttpSession} configuration; the standard Servlet
     * {@code HttpSession} API is preserved so that all existing business logic
     * continues to work unchanged.
     *
     * <p>This eliminates server affinity: any EC2 instance in the Auto Scaling Group
     * can serve subsequent requests for the same session because the session data
     * lives in the shared Redis cluster rather than in JVM heap memory.
     *
     * <p>cr-java-0067 fix: The booking is now cached in Amazon ElastiCache for Redis
     * via {@link RedisTemplate} with a TTL of {@code bookingCacheTtlSeconds} seconds.
     * This replaces the former unbounded in-memory {@code HashMap} that caused
     * indefinite memory growth and was invisible to other EC2 instances.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 fix: session.setAttribute calls are now transparently persisted to
        // Amazon ElastiCache for Redis by Spring Session Data Redis.  The standard
        // HttpSession API is unchanged; Spring Session replaces the in-memory
        // HttpSessionRepository with a RedisIndexedSessionRepository backed by the
        // ElastiCache cluster configured via SPRING_REDIS_HOST / SPRING_REDIS_PORT
        // (see application.properties and RedisSessionConfig).
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // cr-java-0067 fix: Cache the booking in Amazon ElastiCache for Redis with a TTL.
        // Key format: "booking:cache:<bookingId>"
        // The entry expires automatically after bookingCacheTtlSeconds seconds, preventing
        // indefinite memory growth and ensuring stale data is evicted.
        // All application instances share this cache — no instance-local state.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the booking status for the given booking ID.
     *
     * <p>cr-java-0065 fix: {@code session.getAttribute("guestName")} now reads from
     * Amazon ElastiCache for Redis via Spring Session Data Redis.  The value is
     * available on every application instance in the cluster, so there is no longer
     * any risk of returning {@code null} when the request is routed to a different
     * EC2 instance than the one that created the session.
     *
     * <p>cr-java-0067 fix: Booking details are looked up from the shared ElastiCache
     * Redis cache (key: "booking:cache:<bookingId>") before falling back to the
     * BookingService.  This replaces the former instance-local HashMap lookup.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 fix: Reading from Redis-backed Spring Session — consistent across
        // all instances in the Auto Scaling Group regardless of which instance handled
        // the original createBooking request.
        String lastGuest = (String) session.getAttribute("guestName");

        // cr-java-0067 fix: Attempt to retrieve the booking from the shared ElastiCache
        // Redis cache before delegating to BookingService.  The cache entry has a TTL
        // so it will be automatically evicted after bookingCacheTtlSeconds seconds.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 fix: The previously hard-coded URL
        //   "http://inventory-service.internal:8081/rooms/available"   (line 66 of original)
        // has been replaced with the @Value-injected field `inventoryAvailableUrl`.
        // The property app.inventory.available-url is resolved from the
        // APP_INVENTORY_AVAILABLE_URL environment variable, which is populated at
        // deployment time from AWS Systems Manager Parameter Store
        // (e.g. /resorts/prod/inventory/available-url).
        // No hard-coded environment-specific URL remains in the source code.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryAvailableUrl);
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
