package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069: Hard-coded database credentials replaced with AWS Secrets Manager.
    // The secret name comes from environment variable DB_SECRET_NAME.
    private static final String DB_SECRET_NAME = System.getenv("DB_SECRET_NAME");

    // cr-java-0021: Hardcoded infrastructure hostname externalized to environment variable
    private static final String DB_HOST = System.getenv().getOrDefault("DB_HOST", "db-prod.resorts-internal.com");

    // cr-java-0021: Hardcoded payment API endpoint externalized to environment variable
    @Value("${app.payment.endpoint}")
    private String paymentApi;

    // Lazy Secrets Manager client, created on first use
    private SecretsManagerClient secretsClient;
    private JsonNode cachedSecret;
    private long lastFetchTime = 0;
    private static final long CACHE_TTL_MS = 300_000; // 5 minutes

    private SecretsManagerClient getSecretsClient() {
        if (secretsClient == null) {
            secretsClient = SecretsManagerClient.create();
        }
        return secretsClient;
    }

    private JsonNode getSecret() {
        long now = System.currentTimeMillis();
        if (cachedSecret == null || (now - lastFetchTime) > CACHE_TTL_MS) {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(DB_SECRET_NAME)
                    .build();
            GetSecretValueResponse response = getSecretsClient().getSecretValue(request);
            try {
                cachedSecret = new ObjectMapper().readTree(response.secretString());
                lastFetchTime = now;
            } catch (Exception e) {
                // Old failure path: return null so callers handle as before
                return null;
            }
        }
        return cachedSecret;
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // cr-java-0069: Database credentials retrieved from AWS Secrets Manager
        String dbUser = null;
        String dbPass = null;
        try {
            JsonNode secret = getSecret();
            if (secret != null) {
                dbUser = secret.get("username").asText();
                dbPass = secret.get("password").asText();
            }
        } catch (SdkException e) {
            // Old failure path: credentials remain null, connection fails as before
        }

        // SQL injection fix: use parameterized query
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        String confirmCode = md5Hash(bookingId + guestName);

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
        // SQL injection fix: use parameterized query
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

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

    public boolean isRoomAvailable(String roomType) {
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE")
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) {
            return false;
        }
        return true;
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    private String md5Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
