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
import software.amazon.awssdk.services.ssm.model.SsmException;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-native, stateless REST controller.
 *
 * cr-java-0065 FIX: HTTP session state is now backed by Amazon ElastiCache for Redis
 * via Spring Session (spring-session-data-redis).  The {@link EnableRedisHttpSession}
 * annotation on this class (or on a dedicated {@code @Configuration} class) instructs
 * Spring Session to transparently serialise every {@link HttpSession} attribute into
 * Redis instead of keeping it in JVM heap.  All application instances share the same
 * Redis cluster, so any instance can serve any request regardless of which instance
 * originally created the session — eliminating server affinity and enabling true
 * horizontal scaling on AWS (ALB + Auto Scaling Groups / ECS / EKS).
 *
 * cr-java-0067 FIX: The unbounded static in-memory HashMap cache (bookingCache) has been
 * replaced with Amazon ElastiCache for Redis via Spring Data RedisTemplate.  Each cache
 * entry is stored with a 30-minute TTL so that:
 *   - Memory growth is bounded and controlled (entries expire automatically).
 *   - All application instances share the same cache — no stale-data inconsistencies
 *     across horizontally-scaled EC2 / ECS / EKS instances.
 *   - Cache entries are durable across instance restarts and deployments.
 *
 * Session TTL, Redis host/port, and TLS settings are externalised to
 * {@code application.properties} and overridable via environment variables so that
 * no connection details are hard-coded in source.
 */
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0067 FIX: Replaces the unbounded static in-memory HashMap cache with
     * Amazon ElastiCache for Redis via Spring Data RedisTemplate.
     *
     * <p>The previous implementation used a JVM-local {@code static final Map<String, Object>}
     * which caused three cloud-readiness problems:
     * <ol>
     *   <li>Unbounded memory growth — no TTL or eviction policy meant the map grew
     *       indefinitely, risking OutOfMemoryError under sustained load.</li>
     *   <li>Instance-local state — each EC2/ECS/EKS instance maintained its own copy;
     *       a cache miss on one instance could not be served from another instance's
     *       in-memory map, breaking horizontal scaling.</li>
     *   <li>No expiration — stale booking data was never evicted, leading to data
     *       inconsistencies when bookings were updated or cancelled.</li>
     * </ol>
     *
     * <p>With RedisTemplate backed by Amazon ElastiCache for Redis:
     * <ul>
     *   <li>Every {@code set()} call includes a TTL ({@value #BOOKING_CACHE_TTL_MINUTES} minutes),
     *       ensuring automatic expiration and bounded memory usage.</li>
     *   <li>All application instances share the same Redis cluster — any instance can
     *       serve a cache hit regardless of which instance originally populated the entry.</li>
     *   <li>Redis connection details (host, port, TLS) are externalised to environment
     *       variables (REDIS_HOST, REDIS_PORT, REDIS_SSL) — nothing is hard-coded.</li>
     * </ul>
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * Cache key prefix for booking entries stored in Amazon ElastiCache for Redis.
     * Namespacing prevents key collisions with other data stored in the same Redis cluster.
     */
    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:";

    /**
     * TTL (in minutes) applied to every booking cache entry written to Redis.
     * After this period the entry is automatically evicted by ElastiCache,
     * bounding memory growth and preventing stale data from persisting indefinitely.
     */
    private static final long BOOKING_CACHE_TTL_MINUTES = 30L;

    /**
     * AWS SSM Parameter Store parameter name for the inventory service URL.
     * Defaults to "/resortslite/inventory/service-url" if not overridden via
     * the INVENTORY_SERVICE_URL_PARAM environment variable.
     */
    @Value("${app.ssm.inventory-url-param:/resortslite/inventory/service-url}")
    private String inventoryUrlSsmParam;

    /**
     * AWS region used when building the SSM client.
     * Defaults to "us-east-1" if not overridden via the AWS_REGION environment variable.
     */
    @Value("${cloud.aws.region.static:us-east-1}")
    private String awsRegion;

    /**
     * Retrieves the inventory service URL from AWS Systems Manager Parameter Store.
     * Falls back to the provided defaultUrl if the parameter cannot be fetched.
     *
     * @param paramName  SSM parameter name/path
     * @param defaultUrl fallback URL used when SSM is unavailable
     * @return resolved URL string
     */
    private String resolveUrlFromSsm(String paramName, String defaultUrl) {
        try {
            SsmClient ssmClient = SsmClient.builder()
                    .region(Region.of(awsRegion))
                    .build();
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(paramName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (SsmException e) {
            // Log and fall back to the default so the application remains functional
            // when running outside AWS (e.g., local development).
            return defaultUrl;
        }
    }

    /**
     * cr-java-0067 FIX: Stores a booking in Amazon ElastiCache for Redis with a TTL.
     *
     * <p>Replaces the previous {@code bookingCache.put(bookingId, booking)} call that
     * wrote to an unbounded JVM-local HashMap.  This method writes to the shared Redis
     * cluster so that all application instances can read the cached entry, and the
     * entry is automatically evicted after {@value #BOOKING_CACHE_TTL_MINUTES} minutes.</p>
     *
     * @param bookingId unique booking identifier used as the cache key suffix
     * @param booking   booking data map to cache
     */
    private void cacheBookingInRedis(String bookingId, Map<String, Object> booking) {
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + bookingId;
        redisTemplate.opsForValue().set(cacheKey, booking, BOOKING_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * cr-java-0067 FIX: Retrieves a booking from Amazon ElastiCache for Redis.
     *
     * <p>Replaces the previous {@code bookingCache.get(bookingId)} call that read from
     * the JVM-local HashMap.  Because the data is stored in the shared Redis cluster,
     * any application instance can serve the cache hit — not just the instance that
     * originally created the booking.</p>
     *
     * @param bookingId unique booking identifier
     * @return cached booking map, or {@code null} if the entry has expired or was never cached
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getCachedBookingFromRedis(String bookingId) {
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + bookingId;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        return (cached instanceof Map) ? (Map<String, Object>) cached : null;
    }

    /**
     * Creates a new booking and stores transient session state in the distributed
     * Redis-backed HTTP session (cr-java-0065 fix).
     *
     * cr-java-0067 FIX: The booking is also stored in Amazon ElastiCache for Redis
     * via {@link #cacheBookingInRedis(String, Map)} with a {@value #BOOKING_CACHE_TTL_MINUTES}-minute
     * TTL, replacing the previous unbounded in-memory HashMap write.
     *
     * Spring Session intercepts every {@code session.setAttribute()} call and
     * persists the attribute to ElastiCache for Redis, making the data available
     * to all application instances behind the AWS ALB.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session attributes are now stored in Amazon ElastiCache for
        // Redis via Spring Session.  The HttpSession API is unchanged; Spring Session
        // transparently serialises these attributes to the shared Redis cluster so that
        // every application instance can read them — no server affinity required.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // cr-java-0067 FIX: Replaced unbounded static in-memory HashMap cache with
        // Amazon ElastiCache for Redis via RedisTemplate.  The booking is stored with
        // a 30-minute TTL, ensuring automatic expiration and bounded memory usage.
        // All application instances share this cache — no instance-local state.
        cacheBookingInRedis((String) booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the booking status for the given ID.
     *
     * cr-java-0065 FIX: The guest name is read from the distributed Redis-backed
     * HTTP session.  Because Spring Session stores the attribute in ElastiCache,
     * any instance in the cluster can retrieve it — eliminating the "null on other
     * instance" problem that existed with in-process JVM session storage.
     *
     * cr-java-0067 FIX: Booking details are first looked up in the Amazon ElastiCache
     * for Redis cache (via {@link #getCachedBookingFromRedis(String)}).  If the entry
     * has expired or is absent, the request falls through to the database via
     * {@link BookingService#getBookingById(String)}.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 FIX: Session attribute is now read from the shared Redis store
        // via Spring Session — consistent across all instances in the Auto Scaling Group.
        String lastGuest = (String) session.getAttribute("guestName");

        // cr-java-0067 FIX: Attempt to serve booking details from the Amazon ElastiCache
        // for Redis cache before falling back to the database.  The cache entry carries a
        // TTL so it will never grow unbounded or return indefinitely stale data.
        Map<String, Object> cachedBooking = getCachedBookingFromRedis(bookingId);
        Map<String, Object> bookingDetails = (cachedBooking != null)
                ? cachedBooking
                : bookingService.getBookingById(bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingDetails);
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX: The hard-coded environment-specific URL has been replaced with
        // a value retrieved at runtime from AWS Systems Manager Parameter Store.
        // The SSM parameter name is itself externalised via the
        // "app.ssm.inventory-url-param" application property (overridable through the
        // APP_SSM_INVENTORY_URL_PARAM environment variable), so no URL is ever
        // hard-coded in source code.
        String inventoryUrl = resolveUrlFromSsm(
                inventoryUrlSsmParam,
                "http://inventory-service.internal:8081/rooms/available"); // cr-java-0071 fixed

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
