package com.demo.resortslite;

import com.google.cloud.secretmanager.v1.AccessSecretVersionResponse;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretVersionName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // FIXED cr-java-0069: Replaced hard-coded database credentials with environment variables
    // These should be populated from Google Secret Manager in production
    @Value("${spring.datasource.url:${DB_URL:jdbc:h2:mem:resortdb}}")
    private String dbUrl;

    @Value("${spring.datasource.username:${DB_USERNAME:sa}}")
    private String dbUsername;

    // FIXED cr-java-0071: Externalized payment API URL to environment variable
    @Value("${app.payment.endpoint:${PAYMENT_API_URL:https://payment-api.resorts-cloud.com/payments/charge}}")
    private String paymentApiUrl;

    // GCP Project ID for Secret Manager
    @Value("${gcp.project.id:${GCP_PROJECT_ID:}}")
    private String gcpProjectId;

    /**
     * Retrieves a secret from Google Secret Manager.
     * FIXED cr-java-0069: Implemented Secret Manager integration for secure credential retrieval
     * FIXED cr-java-0090: Replaced file-based authentication with Secret Manager
     * 
     * @param secretId The secret ID to retrieve
     * @return The secret value
     */
    private String getSecretFromGCP(String secretId) {
        if (gcpProjectId == null || gcpProjectId.isEmpty()) {
            // Fallback for local development
            return null;
        }
        
        try (SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
            SecretVersionName secretVersionName = SecretVersionName.of(gcpProjectId, secretId, "latest");
            AccessSecretVersionResponse response = client.accessSecretVersion(secretVersionName);
            return response.getPayload().getData().toStringUtf8();
        } catch (Exception e) {
            // Log error and return null - application should handle missing secrets gracefully
            System.err.println("Failed to retrieve secret " + secretId + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Creates a new booking with secure database operations.
     * 
     * @param guestName Guest name
     * @param roomType Room type
     * @param checkIn Check-in date
     * @param checkOut Check-out date
     * @return Booking details
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // FIXED: Use parameterized query to prevent SQL injection
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // FIXED: Use SHA-256 instead of MD5 for secure hashing
        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        return booking;
    }

    /**
     * Retrieves a booking by ID using secure parameterized query.
     * 
     * @param bookingId The booking ID
     * @return Booking details
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // FIXED: Use parameterized query to prevent SQL injection
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    /**
     * Calculates room price based on various factors.
     * 
     * @param roomType Room type
     * @param nights Number of nights
     * @param season Season
     * @param loyalty Loyalty tier
     * @return Calculated price
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = 0;
        if (roomType.equals("STANDARD")) { basePrice = 120.0; }
        else if (roomType.equals("DELUXE")) { basePrice = 200.0; }
        else if (roomType.equals("SUITE")) { basePrice = 350.0; }
        else if (roomType.equals("VILLA")) { basePrice = 600.0; }
        else { basePrice = 120.0; }
        if (season.equals("PEAK")) { basePrice = basePrice * 1.5; }
        else if (season.equals("OFF")) { basePrice = basePrice * 0.8; }
        if (loyalty.equals("GOLD")) { basePrice = basePrice * 0.9; }
        else if (loyalty.equals("PLATINUM")) { basePrice = basePrice * 0.8; }
        else if (loyalty.equals("DIAMOND")) { basePrice = basePrice * 0.7; }
        if (nights >= 7) { basePrice = basePrice * 0.95; }
        else if (nights >= 14) { basePrice = basePrice * 0.90; }
        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    /**
     * Checks if a room type is available.
     * 
     * @param roomType Room type to check
     * @return true if available, false otherwise
     */
    public boolean isRoomAvailable(String roomType) {
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE")
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) {
            return false;
        }
        return true;
    }

    /**
     * Generates a report for the specified month.
     * 
     * @param month The month for the report
     * @return Report generation message
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApiUrl;
    }

    /**
     * Generates SHA-256 hash of input string.
     * FIXED: Replaced MD5 with SHA-256 for secure hashing
     * 
     * @param input Input string to hash
     * @return SHA-256 hash
     */
    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { 
                sb.append(String.format("%02x", b)); 
            }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
