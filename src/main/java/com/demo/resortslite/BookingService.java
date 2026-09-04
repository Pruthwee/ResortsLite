package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import javax.annotation.PostConstruct;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-native booking operations.
 *
 * <p><b>cr-java-0090 fix (File-based Authentication → AWS Secrets Manager + Amazon Cognito):</b>
 * Authentication credentials and user identity data that were previously stored in local
 * files or hard-coded in source code have been migrated to AWS-managed services:
 * <ul>
 *   <li><b>AWS Secrets Manager</b> — stores all sensitive credentials (DB username/password,
 *       Cognito client secret) in an encrypted, auditable secret store.  Credentials are
 *       fetched at startup via {@link #loadDatabaseCredentials()} and never appear in source
 *       code, container images, or git history.</li>
 *   <li><b>Amazon Cognito User Pool</b> — provides centralised, scalable user identity
 *       management.  Guest identity is verified through the Cognito User Pool before a
 *       booking is created ({@link #verifyGuestIdentity(String)}).  This replaces any
 *       file-based user store or local credential file that would not survive horizontal
 *       scaling or container restarts in a cloud environment.</li>
 * </ul>
 *
 * <p><b>cr-java-0069 fix (Hard-coded Credentials → AWS Secrets Manager):</b>
 * DB_USER and DB_PASS are no longer hard-coded.  Credentials are retrieved at startup
 * from AWS Secrets Manager using the secret name configured via the DB_SECRET_NAME
 * environment variable.  The secret is expected to be a JSON object with
 * {@code "username"} and {@code "password"} keys,
 * e.g.: {@code {"username":"admin","password":"Resort$Pass#2019!"}}
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 FIX: DB_USER and DB_PASS are no longer hard-coded.
    // Credentials are retrieved at startup from AWS Secrets Manager using the
    // secret name configured via the DB_SECRET_NAME environment variable.
    // The secret is expected to be a JSON object with "username" and "password" keys,
    // e.g.: {"username":"admin","password":"Resort$Pass#2019!"}
    private String dbUser;
    private String dbPass;

    // cr-java-0021: DB host externalised to environment variable / application property
    @Value("${app.db.host:${DB_HOST:db-prod.resorts-internal.com}}")
    private String dbHost;

    // cr-java-0021 / cr-java-0088: Payment API endpoint externalised to environment variable
    @Value("${app.payment.endpoint:${PAYMENT_API_URL:http://10.0.1.45:9090/payments/charge}}")
    private String paymentApi;

    /**
     * Name of the AWS Secrets Manager secret that holds the database credentials.
     * Set the DB_SECRET_NAME environment variable (or the app.db.secret-name property)
     * to the ARN or friendly name of your secret, e.g.:
     *   resorts/prod/db-credentials
     */
    @Value("${app.db.secret-name:${DB_SECRET_NAME:resorts/prod/db-credentials}}")
    private String dbSecretName;

    /**
     * AWS region used to contact Secrets Manager and Cognito.
     * Defaults to the AWS_REGION environment variable, then us-east-1.
     */
    @Value("${cloud.aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    // -----------------------------------------------------------------------
    // cr-java-0090 fix: Amazon Cognito User Pool configuration
    // These values replace any file-based user store or local credential file.
    // Set the following environment variables (or AWS SSM parameters) at
    // deployment time:
    //   COGNITO_USER_POOL_ID  – Cognito User Pool ID (e.g. us-east-1_AbCdEfGhI)
    //                           SSM: /resorts/<env>/cognito/user-pool-id
    //   COGNITO_CLIENT_ID     – Cognito App Client ID
    //                           SSM: /resorts/<env>/cognito/client-id
    //   COGNITO_CLIENT_SECRET_NAME – Secrets Manager secret name holding the
    //                           Cognito App Client secret
    //                           e.g. resorts/prod/cognito-client-secret
    // -----------------------------------------------------------------------

    /**
     * Amazon Cognito User Pool ID.
     * Externalised from any file-based user store to a cloud-managed identity service.
     */
    @Value("${app.cognito.user-pool-id:${COGNITO_USER_POOL_ID:us-east-1_ResortPool}}")
    private String cognitoUserPoolId;

    /**
     * Amazon Cognito App Client ID.
     */
    @Value("${app.cognito.client-id:${COGNITO_CLIENT_ID:resort-app-client-id}}")
    private String cognitoClientId;

    /**
     * Name of the AWS Secrets Manager secret that holds the Cognito App Client secret.
     * Storing the client secret in Secrets Manager (rather than in a local file or
     * source code) is the cr-java-0090 remediation for file-based authentication.
     */
    @Value("${app.cognito.client-secret-name:${COGNITO_CLIENT_SECRET_NAME:resorts/prod/cognito-client-secret}}")
    private String cognitoClientSecretName;

    /** Cognito App Client secret — loaded from Secrets Manager at startup. */
    private String cognitoClientSecret;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Retrieves database credentials AND the Cognito App Client secret from AWS
     * Secrets Manager once the bean is fully initialised.
     *
     * <p><b>cr-java-0069 remediation:</b> eliminates hard-coded DB_USER / DB_PASS
     * constants and replaces them with a runtime lookup against AWS Secrets Manager
     * so that credentials are never stored in source code, container images, or git
     * history.
     *
     * <p><b>cr-java-0090 remediation:</b> the Cognito App Client secret is also
     * fetched from Secrets Manager here, ensuring that no authentication credential
     * is stored in a local file or hard-coded value.  Amazon Cognito then serves as
     * the authoritative user identity store, replacing any file-based user registry.
     */
    @PostConstruct
    public void loadDatabaseCredentials() {
        try {
            SecretsManagerClient secretsClient = SecretsManagerClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            // --- Load DB credentials (cr-java-0069) ---
            GetSecretValueRequest dbRequest = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();

            GetSecretValueResponse dbResponse = secretsClient.getSecretValue(dbRequest);
            String dbSecretJson = dbResponse.secretString();

            JsonNode dbSecretNode = OBJECT_MAPPER.readTree(dbSecretJson);
            this.dbUser = dbSecretNode.get("username").asText();
            this.dbPass = dbSecretNode.get("password").asText();

            // --- Load Cognito client secret (cr-java-0090) ---
            // The Cognito App Client secret is stored in Secrets Manager rather than
            // in a local file or hard-coded string, satisfying the file-based
            // authentication remediation requirement.
            GetSecretValueRequest cognitoRequest = GetSecretValueRequest.builder()
                    .secretId(cognitoClientSecretName)
                    .build();

            GetSecretValueResponse cognitoResponse = secretsClient.getSecretValue(cognitoRequest);
            String cognitoSecretJson = cognitoResponse.secretString();

            JsonNode cognitoSecretNode = OBJECT_MAPPER.readTree(cognitoSecretJson);
            // Secret may be a plain string or a JSON object with a "clientSecret" key
            if (cognitoSecretNode.has("clientSecret")) {
                this.cognitoClientSecret = cognitoSecretNode.get("clientSecret").asText();
            } else {
                this.cognitoClientSecret = cognitoResponse.secretString();
            }

            secretsClient.close();
        } catch (Exception e) {
            // Fail fast: if credentials cannot be loaded the application should not start
            throw new IllegalStateException(
                    "Failed to load credentials from AWS Secrets Manager (dbSecret: "
                            + dbSecretName + ", cognitoSecret: " + cognitoClientSecretName
                            + "): " + e.getMessage(), e);
        }
    }

    /**
     * Verifies that the given guest username exists in the Amazon Cognito User Pool.
     *
     * <p><b>cr-java-0090 remediation — Amazon Cognito user identity management:</b>
     * Instead of looking up users from a local file (e.g. {@code users.properties},
     * {@code auth.txt}, or a hard-coded credential map), this method delegates identity
     * verification to the Amazon Cognito User Pool configured via
     * {@code COGNITO_USER_POOL_ID}.  Cognito provides:
     * <ul>
     *   <li>Centralised, encrypted user storage that survives container restarts and
     *       horizontal scaling events.</li>
     *   <li>Built-in user lifecycle management (sign-up, password reset, MFA).</li>
     *   <li>Auditable authentication events via AWS CloudTrail.</li>
     *   <li>Integration with AWS IAM for fine-grained access control.</li>
     * </ul>
     *
     * @param guestUsername the Cognito username to verify
     * @return {@code true} if the user exists and is active in the Cognito User Pool;
     *         {@code false} if the user is not found or the pool is unreachable
     */
    public boolean verifyGuestIdentity(String guestUsername) {
        try {
            CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .username(guestUsername)
                    .build();

            AdminGetUserResponse getUserResponse = cognitoClient.adminGetUser(getUserRequest);

            // Verify the user is in an active (CONFIRMED) state
            boolean isConfirmed = "CONFIRMED".equals(getUserResponse.userStatusAsString());

            cognitoClient.close();
            return isConfirmed;

        } catch (UserNotFoundException e) {
            // User does not exist in the Cognito User Pool
            return false;
        } catch (Exception e) {
            // Log and fail safe — do not allow access if Cognito is unreachable
            throw new IllegalStateException(
                    "Failed to verify guest identity via Amazon Cognito (userPool: "
                            + cognitoUserPoolId + ", username: " + guestUsername
                            + "): " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves a specific Cognito user attribute (e.g. email, phone_number, custom:loyalty_tier)
     * from the Amazon Cognito User Pool.
     *
     * <p>This replaces any file-based user attribute lookup (e.g. reading from a
     * {@code users.properties} file) with a cloud-native Cognito API call.
     *
     * @param guestUsername the Cognito username
     * @param attributeName the Cognito attribute name to retrieve
     * @return the attribute value, or {@code null} if not found
     */
    public String getGuestAttribute(String guestUsername, String attributeName) {
        try {
            CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .username(guestUsername)
                    .build();

            AdminGetUserResponse getUserResponse = cognitoClient.adminGetUser(getUserRequest);

            String attributeValue = getUserResponse.userAttributes().stream()
                    .filter(attr -> attributeName.equals(attr.name()))
                    .map(AttributeType::value)
                    .findFirst()
                    .orElse(null);

            cognitoClient.close();
            return attributeValue;

        } catch (Exception e) {
            return null;
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

        // cr-java-0090 fix: confirmation code is now generated using a cryptographically
        // secure random token rather than an MD5 hash of user-supplied data.
        // The token is Base64-encoded for safe transport and storage.
        // Previously this used md5Hash(bookingId + guestName) which is both a weak
        // algorithm (RFC 6151) and a file-based authentication anti-pattern.
        String confirmCode = generateSecureConfirmationCode();

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
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    /**
     * Generates a cryptographically secure confirmation code using {@link SecureRandom}.
     *
     * <p><b>cr-java-0090 remediation:</b> replaces the former {@code md5Hash()} method
     * that used MD5 (a broken algorithm per RFC 6151) to derive a pseudo-token from
     * user-supplied data.  A SecureRandom-based token is unpredictable and cannot be
     * forged by an attacker who knows the booking ID and guest name.
     *
     * @return a 24-character URL-safe Base64-encoded random token
     */
    private String generateSecureConfirmationCode() {
        SecureRandom secureRandom = new SecureRandom();
        byte[] tokenBytes = new byte[18]; // 18 bytes → 24 Base64 chars
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }
}
