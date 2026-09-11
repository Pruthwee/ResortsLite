package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-native REST controller for resort booking operations.
 *
 * <p>Session state is now managed via Spring Session backed by Amazon ElastiCache for Redis
 * (cr-java-0065 fix). The {@link EnableRedisHttpSession} annotation on this class activates
 * Spring Session's Redis-backed {@link HttpSession} implementation, replacing the default
 * in-memory, server-local session store. All {@code session.setAttribute} /
 * {@code session.getAttribute} calls transparently read from and write to the shared Redis
 * cluster, enabling stateless application instances that can be freely load-balanced and
 * auto-scaled across multiple EC2 nodes without sticky sessions or session-loss on failover.
 *
 * <p>cr-java-0067 fix: The former unbounded in-memory {@code HashMap} cache
 * ({@code bookingCache}) has been replaced with a {@link RedisTemplate}-backed
 * distributed cache on Amazon ElastiCache for Redis.  Every cache entry is written
 * with an explicit TTL (default 30 minutes, configurable via
 * {@code app.cache.booking-ttl-minutes}) so entries expire automatically, preventing
 * indefinite memory growth and stale-data inconsistencies across multiple instances.
 * Because the cache is stored in the shared Redis cluster, all application instances
 * read the same data, enabling true stateless horizontal scaling.
 */
@EnableRedisHttpSession
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * FIX cr-java-0067: Distributed Redis cache replacing the former unbounded in-memory
     * {@code HashMap}.  Backed by Amazon ElastiCache for Redis via Spring Data Redis.
     * Each entry is stored with an explicit TTL (see {@link #bookingCacheTtlMinutes}) so
     * the cache never grows indefinitely and stale entries are automatically evicted.
     * The cache is shared across all application instances, ensuring consistent reads
     * regardless of which EC2 node handles a given request.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * TTL (in minutes) applied to every booking cache entry written to Redis.
     * Defaults to 30 minutes; override via the {@code APP_CACHE_BOOKING_TTL_MINUTES}
     * environment variable or the {@code app.cache.booking-ttl-minutes} property.
     */
    @Value("${app.cache.booking-ttl-minutes:30}")
    private long bookingCacheTtlMinutes;

    /** Redis key prefix used for all booking cache entries. */
    private static final String BOOKING_CACHE_PREFIX = "booking:cache:";

    /**
     * AWS SSM Parameter Store parameter name for the inventory service URL.
     * Injected from application properties / environment variable.
     * Replaces the hard-coded URL "http://inventory-service.internal:8081/rooms/available"
     * (cr-java-0071 line 66).
     */
    @Value("${app.ssm.inventory-url-param:/resortslite/inventory/service-url}")
    private String inventoryUrlParamName;

    /**
     * AWS region used when building the SSM client.
     * Injected from application properties / environment variable.
     */
    @Value("${cloud.aws.region:us-east-1}")
    private String awsRegion;

    /**
     * Retrieves the inventory service URL from AWS Systems Manager Parameter Store.
     * This replaces the former hard-coded URL (cr-java-0071 line 66) with a
     * cloud-native, environment-agnostic configuration lookup.
     *
     * @return the inventory service URL stored in SSM Parameter Store
     */
    private String getInventoryUrlFromSsm() {
        SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();

        GetParameterRequest request = GetParameterRequest.builder()
                .name(inventoryUrlParamName)
                .withDecryption(false)
                .build();

        GetParameterResponse response = ssmClient.getParameter(request);
        return response.parameter().value();
    }

    /**
     * Creates a new booking and stores the booking reference in the distributed Redis-backed
     * HTTP session (cr-java-0065 fix). The session is managed by Spring Session + ElastiCache
     * for Redis, so the data is visible to every application instance in the cluster.
     *
     * <p>cr-java-0067 fix: The booking is also cached in the shared Redis cache via
     * {@link RedisTemplate} with an explicit TTL of {@link #bookingCacheTtlMinutes} minutes,
     * replacing the former unbounded in-memory {@code HashMap} cache entry.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIX cr-java-0065: session is now backed by Amazon ElastiCache for Redis via
        // Spring Session (@EnableRedisHttpSession). setAttribute calls write to the shared
        // Redis cluster, making session data available to all application instances.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // FIX cr-java-0067: Store booking in distributed Redis cache with explicit TTL.
        // Replaces the former unbounded in-memory HashMap (bookingCache) that caused
        // indefinite memory growth and stale data across multiple EC2 instances.
        // The entry expires automatically after bookingCacheTtlMinutes minutes, preventing
        // memory leaks and ensuring stale data is not served after the TTL elapses.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the booking status. The guest name is retrieved from the distributed
     * Redis-backed HTTP session (cr-java-0065 fix), ensuring consistent reads regardless
     * of which application instance handles the request.
     *
     * <p>cr-java-0067 fix: Booking details are looked up from the shared Redis cache
     * (with TTL) before falling back to the service layer, replacing the former
     * unbounded in-memory {@code HashMap} lookup.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // FIX cr-java-0065: session.getAttribute reads from the shared Redis cluster via
        // Spring Session, so the value is consistent across all EC2 instances in the cluster.
        String lastGuest = (String) session.getAttribute("guestName");

        // FIX cr-java-0067: Look up booking from the distributed Redis cache (with TTL).
        // If the entry has expired or was never cached, fall back to the service layer.
        String cacheKey = BOOKING_CACHE_PREFIX + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIX cr-java-0071: Replaced hard-coded URL "http://inventory-service.internal:8081/rooms/available"
        // with a value retrieved from AWS Systems Manager Parameter Store.
        // The parameter name is configured via app.ssm.inventory-url-param in application.properties
        // (or the SSM_INVENTORY_URL_PARAM environment variable), enabling environment-agnostic deployments.
        String inventoryUrl = getInventoryUrlFromSsm(); // cr-java-0071 fixed

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
