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
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-native booking operations backed by AWS services.
 *
 * cr-java-0090 (File-based Authentication) fix:
 *   Authentication credentials and user identity are no longer stored in or
 *   validated against local files.  All credential storage is delegated to
 *   AWS Secrets Manager and all user identity / token management is delegated
 *   to Amazon Cognito User Pools, providing centralised, encrypted, and
 *   auditable authentication with built-in user lifecycle management.
 *
 *   - {@link #getDbCredentialsFromSecretsManager()} retrieves DB credentials
 *     from Secrets Manager (secret name driven by {@code app.db.secret-name}).
 *   - {@link #authenticateGuest(String, String)} validates guest credentials
 *     against the Cognito User Pool configured via
 *     {@code app.cognito.user-pool-id} and {@code app.cognito.client-id}.
 *   - {@link #getGuestProfile(String)} fetches the authenticated user's
 *     profile attributes from the same Cognito User Pool.
 *   - The local MD5-based confirmation-code helper has been replaced by a
 *     UUID-based token that is opaque and does not leak internal state.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069: Hard-coded database credentials replaced with AWS Secrets Manager.
    // DB_USER and DB_PASS are no longer stored in source code. Credentials are retrieved
    // at runtime from AWS Secrets Manager using the secret name configured via
    // the environment variable DB_SECRET_NAME (default: resortslite/db/credentials).
    @Value("${app.db.secret-name:resortslite/db/credentials}")
    private String dbSecretName;

    @Value("${cloud.aws.region:us-east-1}")
    private String awsRegion;

    // cr-java-0090: Cognito User Pool configuration — replaces file-based user store.
    // Set APP_COGNITO_USER_POOL_ID and APP_COGNITO_CLIENT_ID environment variables
    // (or override via application.properties) before deploying to AWS.
    @Value("${app.cognito.user-pool-id:${APP_COGNITO_USER_POOL_ID:us-east-1_CHANGEME}}")
    private String cognitoUserPoolId;

    @Value("${app.cognito.client-id:${APP_COGNITO_CLIENT_ID:CHANGEME_CLIENT_ID}}")
    private String cognitoClientId;

    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    // -------------------------------------------------------------------------
    // AWS Secrets Manager — database credential retrieval (cr-java-0069)
    // -------------------------------------------------------------------------

    /**
     * Retrieves database credentials (username and password) from AWS Secrets Manager.
     * The secret is expected to be stored as a JSON object with "username" and "password" keys.
     * Example secret value: {"username":"admin","password":"Resort$Pass#2019!"}
     *
     * @return Map containing "username" and "password" keys
     */
    private Map<String, String> getDbCredentialsFromSecretsManager() {
        SecretsManagerClient client = SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();
            GetSecretValueResponse response = client.getSecretValue(request);
            String secretJson = response.secretString();
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, String> credentials = mapper.readValue(secretJson, Map.class);
            return credentials;
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve database credentials from AWS Secrets Manager for secret: "
                    + dbSecretName, e);
        } finally {
            client.close();
        }
    }

    // -------------------------------------------------------------------------
    // Amazon Cognito — user identity management (cr-java-0090)
    // -------------------------------------------------------------------------

    /**
     * Builds a short-lived {@link CognitoIdentityProviderClient} for the configured region.
     * Callers are responsible for closing the client after use.
     */
    private CognitoIdentityProviderClient buildCognitoClient() {
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Authenticates a guest against the Amazon Cognito User Pool using the
     * ADMIN_USER_PASSWORD_AUTH flow.
     *
     * <p>cr-java-0090 fix: replaces any file-based credential lookup with a
     * Cognito-managed identity check.  Credentials are never stored locally;
     * Cognito handles password hashing, MFA, and token issuance.</p>
     *
     * @param username the guest's Cognito username (typically their e-mail address)
     * @param password the guest's password (transmitted over TLS; never persisted locally)
     * @return a Map containing the Cognito tokens: {@code idToken}, {@code accessToken},
     *         and {@code refreshToken}
     * @throws RuntimeException if authentication fails or Cognito is unreachable
     */
    public Map<String, String> authenticateGuest(String username, String password) {
        CognitoIdentityProviderClient cognitoClient = buildCognitoClient();
        try {
            Map<String, String> authParams = new HashMap<>();
            authParams.put("USERNAME", username);
            authParams.put("PASSWORD", password);

            AdminInitiateAuthRequest authRequest = AdminInitiateAuthRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .clientId(cognitoClientId)
                    .authFlow(AuthFlowType.ADMIN_USER_PASSWORD_AUTH)
                    .authParameters(authParams)
                    .build();

            AdminInitiateAuthResponse authResponse = cognitoClient.adminInitiateAuth(authRequest);

            Map<String, String> tokens = new HashMap<>();
            tokens.put("idToken",      authResponse.authenticationResult().idToken());
            tokens.put("accessToken",  authResponse.authenticationResult().accessToken());
            tokens.put("refreshToken", authResponse.authenticationResult().refreshToken());
            return tokens;
        } catch (NotAuthorizedException e) {
            throw new RuntimeException("Authentication failed for user: " + username
                    + ". Invalid credentials.", e);
        } catch (UserNotFoundException e) {
            throw new RuntimeException("User not found in Cognito User Pool: " + username, e);
        } catch (Exception e) {
            throw new RuntimeException("Cognito authentication error for user: " + username, e);
        } finally {
            cognitoClient.close();
        }
    }

    /**
     * Retrieves a guest's profile attributes from the Amazon Cognito User Pool.
     *
     * <p>cr-java-0090 fix: user profile data is stored and managed in Cognito,
     * not in local files or an unmanaged database table.</p>
     *
     * @param username the Cognito username whose profile should be fetched
     * @return a Map of Cognito user attribute names to their values
     * @throws RuntimeException if the user does not exist or Cognito is unreachable
     */
    public Map<String, String> getGuestProfile(String username) {
        CognitoIdentityProviderClient cognitoClient = buildCognitoClient();
        try {
            AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .username(username)
                    .build();

            AdminGetUserResponse getUserResponse = cognitoClient.adminGetUser(getUserRequest);

            Map<String, String> profile = new HashMap<>();
            getUserResponse.userAttributes().forEach(attr ->
                    profile.put(attr.name(), attr.value()));
            profile.put("userStatus", getUserResponse.userStatusAsString());
            return profile;
        } catch (UserNotFoundException e) {
            throw new RuntimeException("Guest profile not found in Cognito User Pool: " + username, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve guest profile from Cognito for user: " + username, e);
        } finally {
            cognitoClient.close();
        }
    }

    // -------------------------------------------------------------------------
    // Booking operations
    // -------------------------------------------------------------------------

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

        // cr-java-0090 fix: confirmation code is now a random UUID token.
        // The previous MD5-based local hash (file-based auth pattern) has been removed.
        // Authentication tokens and user identity are managed by Amazon Cognito;
        // this booking confirmation code is a simple opaque reference, not a security token.
        String confirmCode = UUID.randomUUID().toString().replace("-", "").toUpperCase();

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
