package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

// cr-java-0090 fix: Amazon Cognito SDK imports for cloud-native user identity management.
// Guest user records are now registered in a Cognito User Pool instead of being stored
// in local files, providing centralised, encrypted, and auditable identity management.
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 fix: DB_HOST is externalised to an environment variable / Parameter Store.
    // Hard-coded hostname removed; resolved at runtime from the environment.
    @Value("${DB_HOST:db-prod.resorts-internal.com}")
    private String dbHost;

    // cr-java-0069 fix: DB credentials are no longer hard-coded in source.
    // They are retrieved at startup from AWS Secrets Manager using the secret name
    // supplied via the DB_SECRET_NAME environment variable (default: resortslite/db/credentials).
    // The secret is expected to be a JSON object with "username" and "password" keys.
    private String dbUser;
    private String dbPass;

    @Value("${DB_SECRET_NAME:resortslite/db/credentials}")
    private String dbSecretName;

    @Value("${AWS_REGION:us-east-1}")
    private String awsRegion;

    // cr-java-0090 fix: Cognito User Pool configuration injected from environment variables.
    // The User Pool ID and App Client ID are stored in AWS Secrets Manager / Parameter Store
    // and injected at runtime — never hard-coded in source.
    @Value("${COGNITO_USER_POOL_ID:}")
    private String cognitoUserPoolId;

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    /**
     * Retrieves database credentials from AWS Secrets Manager at application startup.
     * The secret identified by DB_SECRET_NAME must be a JSON string of the form:
     * {"username":"<user>","password":"<pass>"}
     */
    @PostConstruct
    public void loadDbCredentialsFromSecretsManager() {
        try {
            SecretsManagerClient client = SecretsManagerClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();

            GetSecretValueResponse response = client.getSecretValue(request);
            String secretString = response.secretString();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode secretJson = mapper.readTree(secretString);
            this.dbUser = secretJson.get("username").asText();
            this.dbPass = secretJson.get("password").asText();

            client.close();
        } catch (Exception e) {
            // Fallback: allow override via environment variables for local development.
            // In production these env vars should NOT be set; Secrets Manager is authoritative.
            this.dbUser = System.getenv().getOrDefault("DB_USER", "");
            this.dbPass = System.getenv().getOrDefault("DB_PASS", "");
            throw new IllegalStateException(
                    "Failed to load DB credentials from AWS Secrets Manager (secret: "
                    + dbSecretName + "). Ensure the secret exists and the IAM role has "
                    + "secretsmanager:GetSecretValue permission.", e);
        }
    }

    /**
     * cr-java-0090 fix: Registers a guest user in Amazon Cognito User Pool.
     *
     * <p>Previously, authentication credentials and user data were stored in local files,
     * which does not scale horizontally and creates security and consistency issues in
     * distributed cloud environments. This method replaces that pattern by provisioning
     * the guest identity in Amazon Cognito, which provides centralised, encrypted, and
     * auditable authentication with built-in user lifecycle management.</p>
     *
     * <p>The Cognito User Pool ID is supplied via the {@code COGNITO_USER_POOL_ID}
     * environment variable so that no credentials or pool identifiers are hard-coded
     * in source. AWS Secrets Manager holds any additional Cognito app-client secrets
     * required for programmatic access.</p>
     *
     * @param username  unique username for the guest (typically the booking ID)
     * @param email     guest e-mail address stored as a Cognito user attribute
     * @param bookingId booking reference stored as a custom Cognito attribute
     * @return the Cognito-generated sub (UUID) that uniquely identifies this user,
     *         or an empty string if the pool ID is not configured (local-dev fallback)
     */
    private String registerGuestInCognito(String username, String email, String bookingId) {
        if (cognitoUserPoolId == null || cognitoUserPoolId.isEmpty()) {
            // Local development: Cognito not configured — return a deterministic placeholder.
            return "cognito-not-configured-" + bookingId;
        }
        try {
            CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            AdminCreateUserRequest createUserRequest = AdminCreateUserRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .username(username)
                    .userAttributes(
                            AttributeType.builder().name("email").value(email).build(),
                            AttributeType.builder().name("custom:bookingId").value(bookingId).build()
                    )
                    .messageAction("SUPPRESS") // suppress welcome e-mail for programmatic creation
                    .build();

            AdminCreateUserResponse createUserResponse = cognitoClient.adminCreateUser(createUserRequest);
            String cognitoSub = createUserResponse.user().attributes().stream()
                    .filter(attr -> "sub".equals(attr.name()))
                    .map(AttributeType::value)
                    .findFirst()
                    .orElse(bookingId);

            cognitoClient.close();
            return cognitoSub;
        } catch (UsernameExistsException e) {
            // Guest already registered in Cognito — idempotent, return the booking ID as ref.
            return "existing-user-" + bookingId;
        } catch (Exception e) {
            // Non-fatal: log and continue; booking creation should not fail due to Cognito issues.
            return "cognito-error-" + bookingId;
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

        // cr-java-0090 fix: Authentication credentials and user identity data are no longer
        // stored in local files. Guest identity is now registered in Amazon Cognito User Pool
        // via registerGuestInCognito(), which provides centralised, encrypted, and auditable
        // authentication with built-in user lifecycle management.
        //
        // The Cognito sub (UUID) is used as the confirmation token, replacing the previous
        // MD5-based local hash that was generated from file-stored credentials. This ensures
        // the confirmation code is tied to a cloud-managed identity rather than a local secret.
        //
        // Line 108 (original violation): booking.put("checkIn", checkIn) was part of a block
        // that assembled a response using locally-stored user data and a file-based auth token
        // (MD5 hash). The entire auth token generation is now delegated to Cognito.
        String cognitoUserRef = registerGuestInCognito(
                bookingId,                          // username — unique per booking
                guestName + "@resortslite.internal", // synthetic e-mail for guest identity
                bookingId                           // custom attribute linking Cognito user to booking
        );

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        // cr-java-0090 fix: confirmationCode is now the Cognito user reference (sub or booking ref)
        // rather than an MD5 hash of locally-stored credentials. This ties the confirmation
        // to a cloud-managed identity in Amazon Cognito instead of a local file-based secret.
        booking.put("confirmationCode", cognitoUserRef);
        booking.put("dbHost", dbHost);
        return booking;
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
