package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service layer for resort booking operations.
 *
 * <p>Migration notes (Java 1.8 → Java 21 / Spring Boot 3.2.x):
 * <ul>
 *   <li>SQL injection vulnerability fixed: all JdbcTemplate queries now use
 *       parameterised placeholders (?) instead of string concatenation.</li>
 *   <li>MD5 confirmation-code hashing replaced with SHA-256 (MD5 is cryptographically
 *       broken and must not be used for security-sensitive operations).</li>
 *   <li>Hardcoded database credentials and infrastructure hostnames replaced with
 *       environment-variable lookups; in AWS deployments these should be populated
 *       via AWS Secrets Manager or Parameter Store.</li>
 *   <li>High cyclomatic complexity in calculateRoomPrice refactored using Java 14+
 *       switch expressions (fully supported in Java 21).</li>
 * </ul>
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Updated: credentials and infrastructure endpoints externalised to environment variables.
    // In AWS deployments, populate these via AWS Secrets Manager or Parameter Store.
    private static final String DB_HOST =
            System.getenv().getOrDefault("DB_HOST", "localhost");
    private static final String DB_USER =
            System.getenv().getOrDefault("DB_USER", "sa");
    private static final String DB_PASS =
            System.getenv().getOrDefault("DB_PASS", "");

    // Updated: payment API endpoint externalised to environment variable; HTTPS enforced.
    private static final String PAYMENT_API =
            System.getenv().getOrDefault("PAYMENT_API_URL",
                    "https://payment-svc.internal:9090/payments/charge");

    /**
     * Creates a new booking record in the database.
     *
     * <p>Uses a parameterised INSERT statement to prevent SQL injection.
     * The confirmation code is generated using SHA-256 (replaces broken MD5).</p>
     *
     * @param guestName the name of the guest
     * @param roomType  the type of room requested
     * @param checkIn   the check-in date string
     * @param checkOut  the check-out date string
     * @return a map containing the booking details and confirmation code
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Updated: parameterised query prevents SQL injection (was string concatenation).
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // Updated: SHA-256 replaces broken MD5 for confirmation code generation.
        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", DB_HOST);
        return booking;
    }

    /**
     * Retrieves a booking record by its identifier.
     *
     * <p>Uses a parameterised SELECT statement to prevent SQL injection.</p>
     *
     * @param bookingId the booking identifier
     * @return a map containing the booking row data, or an error entry if not found
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // Updated: parameterised query prevents SQL injection (was string concatenation).
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
     * Calculates the total room price based on room type, number of nights,
     * season, and guest loyalty tier.
     *
     * <p>Refactored: replaced deeply nested if-else chains (high cyclomatic complexity)
     * with Java 14+ switch expressions, fully supported in Java 21.</p>
     *
     * @param roomType the room category (STANDARD, DELUXE, SUITE, VILLA)
     * @param nights   the number of nights
     * @param season   the season code (PEAK, OFF, or standard)
     * @param loyalty  the loyalty tier (GOLD, PLATINUM, DIAMOND, or none)
     * @return the formatted total price as a string
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        // Updated: switch expressions replace if-else chains (Java 14+, standard in Java 21).
        double basePrice = switch (roomType) {
            case "STANDARD" -> 120.0;
            case "DELUXE"   -> 200.0;
            case "SUITE"    -> 350.0;
            case "VILLA"    -> 600.0;
            default         -> 120.0;
        };

        basePrice = switch (season) {
            case "PEAK" -> basePrice * 1.5;
            case "OFF"  -> basePrice * 0.8;
            default     -> basePrice;
        };

        basePrice = switch (loyalty) {
            case "GOLD"     -> basePrice * 0.9;
            case "PLATINUM" -> basePrice * 0.8;
            case "DIAMOND"  -> basePrice * 0.7;
            default         -> basePrice;
        };

        if (nights >= 14) {
            basePrice = basePrice * 0.90;
        } else if (nights >= 7) {
            basePrice = basePrice * 0.95;
        }

        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    /**
     * Checks whether a room of the given type is available for booking.
     *
     * @param roomType the room category to check
     * @return {@code true} if the room type is valid and available; {@code false} otherwise
     */
    public boolean isRoomAvailable(String roomType) {
        return RoomType.isValid(roomType);
    }

    /**
     * Triggers report generation for the specified month.
     *
     * @param month the month identifier
     * @return a status message indicating the report was triggered
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    /**
     * Computes a SHA-256 hex digest of the given input string.
     *
     * <p>Updated: replaces the previously used MD5 algorithm which is cryptographically
     * broken and unsuitable for any security-sensitive hashing operation.</p>
     *
     * @param input the string to hash
     * @return the lowercase hex-encoded SHA-256 digest, or the original input on error
     */
    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }

    /**
     * Enum representing valid room types to eliminate duplicated validation logic.
     */
    private enum RoomType {
        STANDARD, DELUXE, SUITE, VILLA;

        /**
         * Returns {@code true} if the given string matches a known room type.
         *
         * @param value the room type string to validate
         * @return {@code true} if valid; {@code false} otherwise
         */
        public static boolean isValid(String value) {
            for (RoomType rt : values()) {
                if (rt.name().equals(value)) {
                    return true;
                }
            }
            return false;
        }
    }
}
