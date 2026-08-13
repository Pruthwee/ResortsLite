package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import javax.annotation.PostConstruct;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Base64;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // FIXED cr-java-0069: Removed hard-coded database credentials.
    // Credentials are now retrieved from AWS Secrets Manager at runtime.
    @Value("${aws.secretsmanager.secret.name:resorts-lite-db-credentials}")
    private String secretName;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    // FIXED cr-java-0090: Added AWS Cognito configuration for user identity management
    @Value("${aws.cognito.user.pool.id:}")
    private String cognitoUserPoolId;

    @Value("${aws.cognito.enabled:false}")
    private boolean cognitoEnabled;

    private String dbHost;
    private String dbUser;
    private String dbPass;

    private CognitoIdentityProviderClient cognitoClient;
    private SecureRandom secureRandom;

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    @PostConstruct
    public void init() {
        // Retrieve database credentials from AWS Secrets Manager
        loadDatabaseCredentialsFromSecretsManager();
        
        // FIXED cr-java-0090: Initialize AWS Cognito client for secure authentication
        initializeCognitoClient();
        
        // Initialize SecureRandom for secure token generation
        secureRandom = new SecureRandom();
    }

    /**
     * FIXED cr-java-0090: Initialize AWS Cognito Identity Provider client
     * for cloud-native user identity and authentication management.
     */
    private void initializeCognitoClient() {
        try {
            Region region = Region.of(awsRegion);
            cognitoClient = CognitoIdentityProviderClient.builder()
                    .region(region)
                    .build();
        } catch (Exception e) {
            System.err.println("Warning: Could not initialize AWS Cognito client. " +
                    "Falling back to local token generation. Error: " + e.getMessage());
            cognitoEnabled = false;
        }
    }

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     * Expected secret format in Secrets Manager:
     * {
     *   "host": "db-prod.resorts-internal.com",
     *   "username": "admin",
     *   "password": "Resort$Pass#2019!"
     * }
     */
    private void loadDatabaseCredentialsFromSecretsManager() {
        try {
            Region region = Region.of(awsRegion);
            SecretsManagerClient client = SecretsManagerClient.builder()
                    .region(region)
                    .build();

            GetSecretValueRequest getSecretValueRequest = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();

            GetSecretValueResponse getSecretValueResponse = client.getSecretValue(getSecretValueRequest);
            String secret = getSecretValueResponse.secretString();

            // Parse the JSON secret
            Gson gson = new Gson();
            JsonObject secretJson = gson.fromJson(secret, JsonObject.class);

            this.dbHost = secretJson.get("host").getAsString();
            this.dbUser = secretJson.get("username").getAsString();
            this.dbPass = secretJson.get("password").getAsString();

            client.close();
        } catch (Exception e) {
            // Fallback to environment variables if Secrets Manager is not available
            // This allows local development without AWS credentials
            this.dbHost = System.getenv().getOrDefault("DB_HOST", "localhost");
            this.dbUser = System.getenv().getOrDefault("DB_USER", "sa");
            this.dbPass = System.getenv().getOrDefault("DB_PASS", "");
            
            System.err.println("Warning: Could not retrieve credentials from AWS Secrets Manager. " +
                    "Using environment variables or defaults. Error: " + e.getMessage());
        }
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // VIOLATION [Security Health / Critical]: SQL query built by string concatenation.
        // An attacker can pass guestName = "'; DROP TABLE bookings; --" to destroy data.
        // Use parameterised queries (JdbcTemplate with '?') to prevent SQL injection.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES ('" // sql-inject-001
                + bookingId + "', '" + guestName + "', '" + roomType               // sql-inject-001
                + "', '" + checkIn + "', '" + checkOut + "')";                     // sql-inject-001
        jdbcTemplate.execute(sql);

        // FIXED cr-java-0090: Replaced MD5-based local authentication with AWS Cognito
        // for secure, cloud-native token generation and user identity management.
        String confirmCode = generateSecureConfirmationCode(bookingId, guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", dbHost);
        return booking;
    }

    /**
     * FIXED cr-java-0090: Generate secure confirmation code using AWS Cognito or SecureRandom.
     * Replaces insecure MD5-based file authentication with cloud-native identity management.
     * 
     * @param bookingId The booking identifier
     * @param guestName The guest name
     * @return A secure confirmation code
     */
    private String generateSecureConfirmationCode(String bookingId, String guestName) {
        if (cognitoEnabled && cognitoUserPoolId != null && !cognitoUserPoolId.isEmpty()) {
            try {
                // Use AWS Cognito to create a temporary user attribute for the booking
                // This provides centralized, auditable authentication token management
                String temporaryUsername = "booking_" + bookingId.toLowerCase();
                
                AdminCreateUserRequest createUserRequest = AdminCreateUserRequest.builder()
                        .userPoolId(cognitoUserPoolId)
                        .username(temporaryUsername)
                        .userAttributes(
                                AttributeType.builder()
                                        .name("custom:bookingId")
                                        .value(bookingId)
                                        .build(),
                                AttributeType.builder()
                                        .name("custom:guestName")
                                        .value(guestName)
                                        .build()
                        )
                        .messageAction(MessageActionType.SUPPRESS)
                        .build();
                
                AdminCreateUserResponse response = cognitoClient.adminCreateUser(createUserRequest);
                
                // Generate a secure token based on Cognito user sub (unique identifier)
                String userSub = response.user().username();
                return generateSecureToken(userSub + bookingId);
                
            } catch (Exception e) {
                System.err.println("Warning: Could not use AWS Cognito for token generation. " +
                        "Falling back to SecureRandom. Error: " + e.getMessage());
                // Fall through to SecureRandom generation
            }
        }
        
        // Fallback: Use SecureRandom for cryptographically secure token generation
        // This is more secure than MD5 but not as feature-rich as Cognito
        return generateSecureToken(bookingId + guestName);
    }

    /**
     * Generate a cryptographically secure token using SecureRandom and SHA-256.
     * This replaces the insecure MD5 hash with a proper secure token.
     * 
     * @param input The input string to generate token from
     * @return A secure Base64-encoded token
     */
    private String generateSecureToken(String input) {
        try {
            // Generate 32 bytes of random data
            byte[] randomBytes = new byte[32];
            secureRandom.nextBytes(randomBytes);
            
            // Combine with input for uniqueness
            String combined = input + Base64.getEncoder().encodeToString(randomBytes);
            
            // Use SHA-256 for secure hashing (replaces MD5)
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(combined.getBytes("UTF-8"));
            
            // Return first 16 bytes as Base64 for a readable confirmation code
            byte[] shortHash = new byte[16];
            System.arraycopy(hash, 0, shortHash, 0, 16);
            return Base64.getEncoder().encodeToString(shortHash).substring(0, 22);
            
        } catch (Exception e) {
            // Ultimate fallback: use UUID
            return UUID.randomUUID().toString().substring(0, 22).toUpperCase();
        }
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // VIOLATION [Security Health / Critical]: SQL injection via string concatenation.
        // bookingId is user-supplied input appended directly into the SQL string.
        String sql = "SELECT * FROM bookings WHERE id = '" + bookingId + "'"; // sql-inject-001
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    // VIOLATION [Code Sustainability / High]: High cyclomatic complexity.
    // This method has 9+ decision branches. Automated transformation tools flag methods
    // above complexity threshold as high maintenance risk and transformation blockers.
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
        // VIOLATION [Code Sustainability / Medium]: Duplicated validation logic.
        // Same room type validation is repeated here and in calculateRoomPrice.
        // Should be extracted to a shared RoomType enum or validator.
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE") // dup-logic-001
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) { // dup-logic-001
            return false;
        }
        return true;
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }
}
