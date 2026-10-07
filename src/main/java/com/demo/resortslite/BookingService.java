package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/*
 * JAVA8_TO_25_CRYPTOGRAPHY_AND_TLS (Issue #4 — high):
 *   MD5 is disabled in the Java 25 security policy (jdk.security.legacyAlgorithms).
 *   Replaced md5Hash() with sha256Hash() using MessageDigest("SHA-256").
 *
 * JAVA8_TO_25_CHARSET_EXPLICIT (Issue #8 — medium):
 *   getBytes() is now called with StandardCharsets.UTF_8 to avoid relying on the
 *   platform default charset. Java 18+ defaults to UTF-8 (JEP 400), but explicit
 *   charset specification is required for deterministic behaviour across all JVMs.
 */
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // VIOLATION [Security Health / Critical]: Hardcoded database credentials in source code.
    // Must be externalised to AWS Secrets Manager or Parameter Store.
    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021
    private static final String DB_USER = "admin";                         // sec-cred-001
    private static final String DB_PASS = "Resort$Pass#2019!";             // sec-cred-001

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // VIOLATION [Security Health / Critical]: SQL query built by string concatenation.
        // Use parameterised queries (JdbcTemplate with '?') to prevent SQL injection.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES ('" // sql-inject-001
                + bookingId + "', '" + guestName + "', '" + roomType               // sql-inject-001
                + "', '" + checkIn + "', '" + checkOut + "')";                     // sql-inject-001
        jdbcTemplate.execute(sql);

        /*
         * JAVA8_TO_25_CRYPTOGRAPHY_AND_TLS: Replaced broken MD5 hash with SHA-256.
         * MD5 is disabled/deprecated in Java 25 security policy for security-sensitive use.
         * SHA-256 is the recommended replacement for non-password hashing.
         * Also fixed: getBytes() now uses StandardCharsets.UTF_8 explicitly (Issue #8).
         */
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

    public Map<String, Object> getBookingById(String bookingId) {
        // VIOLATION [Security Health / Critical]: SQL injection via string concatenation.
        String sql = "SELECT * FROM bookings WHERE id = '" + bookingId + "'"; // sql-inject-001
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    // VIOLATION [Code Sustainability / High]: High cyclomatic complexity (9+ branches).
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = 0;
        if (roomType.equals("STANDARD"))      { basePrice = 120.0; }
        else if (roomType.equals("DELUXE"))   { basePrice = 200.0; }
        else if (roomType.equals("SUITE"))    { basePrice = 350.0; }
        else if (roomType.equals("VILLA"))    { basePrice = 600.0; }
        else                                  { basePrice = 120.0; }

        if (season.equals("PEAK"))            { basePrice = basePrice * 1.5; }
        else if (season.equals("OFF"))        { basePrice = basePrice * 0.8; }

        if (loyalty.equals("GOLD"))           { basePrice = basePrice * 0.9; }
        else if (loyalty.equals("PLATINUM"))  { basePrice = basePrice * 0.8; }
        else if (loyalty.equals("DIAMOND"))   { basePrice = basePrice * 0.7; }

        if (nights >= 14)                     { basePrice = basePrice * 0.90; }
        else if (nights >= 7)                 { basePrice = basePrice * 0.95; }

        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    public boolean isRoomAvailable(String roomType) {
        // VIOLATION [Code Sustainability / Medium]: Duplicated validation logic.
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE") // dup-logic-001
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) { // dup-logic-001
            return false;
        }
        return true;
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    /**
     * JAVA8_TO_25_CRYPTOGRAPHY_AND_TLS: SHA-256 hash implementation.
     * Replaces the former md5Hash() method. MD5 is a broken algorithm disabled
     * in the Java 25 security policy (jdk.security.legacyAlgorithms property).
     * SHA-256 is the recommended replacement for non-password hashing use cases.
     *
     * JAVA8_TO_25_CHARSET_EXPLICIT: input.getBytes() now uses StandardCharsets.UTF_8
     * to guarantee consistent byte encoding regardless of the JVM platform default.
     */
    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // JAVA8_TO_25_CHARSET_EXPLICIT: Explicit UTF-8 charset — no platform default reliance
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
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
