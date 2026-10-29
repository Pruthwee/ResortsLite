package com.demo.resortslite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import javax.annotation.PreDestroy;
import javax.servlet.http.HttpSession;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Booking cache lives in Amazon ElastiCache for Redis, shared by all instances, every entry with a TTL.
    private static final String BOOKING_CACHE_PREFIX = "booking:";
    private static final Duration BOOKING_CACHE_TTL = Duration.ofSeconds(3600);

    private static final String INVENTORY_URL_PARAMETER = "inventory-availability-url";

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${app.inventory.availability.url}")
    private String inventoryAvailabilityUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SsmClient ssmClient;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // VIOLATION cr-java-0065 [Cloud Compatibility / Mandatory]: Booking state stored in
        // HTTP session memory. AWS ALB distributes requests across EC2 instances — session
        // data on instance A is invisible to instance B. Auto-scaling and failover breaks.
        session.setAttribute("lastBooking", booking); // cr-java-0065
        session.setAttribute("guestName", guestName); // cr-java-0065

        try {
            redisTemplate.opsForValue().set(BOOKING_CACHE_PREFIX + booking.get("bookingId"),
                    objectMapper.writeValueAsString(booking), BOOKING_CACHE_TTL);
        } catch (DataAccessException | JsonProcessingException e) {
            // the cache is best effort: a booking is still confirmed when the cache cannot be reached
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

        // VIOLATION cr-java-0065 [Cloud Compatibility / Mandatory]: Reading business state
        // from HTTP session — will return null on any other instance in the cluster.
        String lastGuest = (String) session.getAttribute("guestName"); // cr-java-0065

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
        String inventoryUrl = resolveUrl(INVENTORY_URL_PARAMETER, inventoryAvailabilityUrl);

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

    private synchronized SsmClient ssm() {
        if (ssmClient == null) {
            ssmClient = SsmClient.create();
        }
        return ssmClient;
    }

    /**
     * Reads the whole URL from Parameter Store under APP_PARAMETER_PATH; when the path is not set
     * or the call fails, the URL from application.properties is used, as before.
     */
    private String resolveUrl(String parameterName, String fallback) {
        String path = System.getenv("APP_PARAMETER_PATH");
        if (path != null && !path.isEmpty()) {
            try {
                String name = path.endsWith("/") ? path + parameterName : path + "/" + parameterName;
                return ssm().getParameter(GetParameterRequest.builder()
                        .name(name)
                        .withDecryption(true)
                        .build()).parameter().value();
            } catch (SdkException e) {
                // fall through to the configured URL
            }
        }
        return fallback;
    }

    @PreDestroy
    void closeSsmClient() {
        if (ssmClient != null) {
            ssmClient.close();
        }
    }
}
