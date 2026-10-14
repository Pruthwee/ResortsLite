package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // NOTE: Externalise credentials to environment variables / AWS Secrets Manager in production.
    private static final String DB_HOST = "db-prod.resorts-internal.com";
    private static final String DB_USER = "admin";
    private static final String DB_PASS = "Resort$Pass#2019!";

    // NOTE: Externalise service endpoints to environment variables / Parameter Store in production.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge";

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // NOTE: Use parameterised queries in production to prevent SQL injection.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES ('"
                + bookingId + "', '" + guestName + "', '" + roomType
                + "', '" + checkIn + "', '" + checkOut + "')";
        jdbcTemplate.execute(sql);

        // Updated: SHA-256 replaces MD5 — MD5 is a broken algorithm disabled in Java 25
        // security policy (jdk.security.legacyAlgorithms). SHA-256 is the recommended
        // minimum for confirmation code generation.
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
        // NOTE: Use parameterised queries in production to prevent SQL injection.
        String sql = "SELECT * FROM bookings WHERE id = '" + bookingId + "'";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice;
        switch (roomType) {
            case "DELUXE"   -> basePrice = 200.0;
            case "SUITE"    -> basePrice = 350.0;
            case "VILLA"    -> basePrice = 600.0;
            default         -> basePrice = 120.0; // STANDARD and unknown types
        }
        if ("PEAK".equals(season))      { basePrice *= 1.5; }
        else if ("OFF".equals(season))  { basePrice *= 0.8; }

        if ("GOLD".equals(loyalty))         { basePrice *= 0.9; }
        else if ("PLATINUM".equals(loyalty)) { basePrice *= 0.8; }
        else if ("DIAMOND".equals(loyalty))  { basePrice *= 0.7; }

        if (nights >= 14)      { basePrice *= 0.90; }
        else if (nights >= 7)  { basePrice *= 0.95; }

        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    public boolean isRoomAvailable(String roomType) {
        return switch (roomType) {
            case "STANDARD", "DELUXE", "SUITE", "VILLA" -> true;
            default -> false;
        };
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    /**
     * Computes a SHA-256 hex digest of the given input string.
     * <p>
     * MD5 was replaced with SHA-256 for Java 25 compatibility: MD5 is listed in
     * {@code jdk.security.legacyAlgorithms} and may be restricted by the JDK security
     * policy in future releases.
     *
     * @param input the string to hash
     * @return lowercase hex-encoded SHA-256 digest, or the original input on error
     */
    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
