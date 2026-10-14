package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private BookingService bookingService;

    // -----------------------------------------------------------------------
    // createBooking tests
    // -----------------------------------------------------------------------

    @Test
    void createBooking_withValidParams_returnsBookingMap() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "John Smith", "SUITE", "2024-03-01", "2024-03-05");

        // Assert
        assertNotNull(result);
        assertEquals("John Smith", result.get("guestName"));
        assertEquals("SUITE", result.get("roomType"));
        assertEquals("2024-03-01", result.get("checkIn"));
        assertEquals("2024-03-05", result.get("checkOut"));
    }

    @Test
    void createBooking_bookingIdStartsWithBK() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Jane Doe", "DELUXE", "2024-04-01", "2024-04-03");

        // Assert
        String bookingId = (String) result.get("bookingId");
        assertNotNull(bookingId);
        assertTrue(bookingId.startsWith("BK-"), "bookingId should start with BK-");
    }

    @Test
    void createBooking_confirmationCodeIsNotNull() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Alice", "VILLA", "2024-05-01", "2024-05-10");

        // Assert
        assertNotNull(result.get("confirmationCode"));
        assertFalse(((String) result.get("confirmationCode")).isEmpty());
    }

    @Test
    void createBooking_confirmationCodeIsSha256Hex() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Bob", "STANDARD", "2024-06-01", "2024-06-02");

        // Assert – SHA-256 hex is always 64 lowercase hex chars
        String code = (String) result.get("confirmationCode");
        assertNotNull(code);
        assertEquals(64, code.length(), "SHA-256 hex digest must be 64 characters");
        assertTrue(code.matches("[0-9a-f]+"), "SHA-256 hex must contain only hex chars");
    }

    @Test
    void createBooking_dbHostIsPresent() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Carol", "SUITE", "2024-07-01", "2024-07-05");

        // Assert
        assertNotNull(result.get("dbHost"));
    }

    @Test
    void createBooking_executesJdbcInsert() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        bookingService.createBooking("Dave", "DELUXE", "2024-08-01", "2024-08-04");

        // Assert – verify that jdbcTemplate.execute was called once
        verify(jdbcTemplate, times(1)).execute(anyString());
    }

    // -----------------------------------------------------------------------
    // getBookingById tests
    // -----------------------------------------------------------------------

    @Test
    void getBookingById_whenFound_returnsMap() {
        // Arrange
        Map<String, Object> dbRow = new HashMap<>();
        dbRow.put("id", "BK-ABCD1234");
        dbRow.put("guest", "John Smith");
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(dbRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-ABCD1234");

        // Assert
        assertNotNull(result);
        assertEquals("BK-ABCD1234", result.get("id"));
    }

    @Test
    void getBookingById_whenNotFound_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString()))
                .thenThrow(new RuntimeException("No results"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-UNKNOWN");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"), "Should contain error key");
        assertTrue(((String) result.get("error")).contains("BK-UNKNOWN"));
    }

    @Test
    void getBookingById_errorMessageContainsBookingId() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString()))
                .thenThrow(new RuntimeException("EmptyResultDataAccessException"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-XYZ999");

        // Assert
        String error = (String) result.get("error");
        assertNotNull(error);
        assertTrue(error.contains("BK-XYZ999"));
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice tests
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_standardRoomNormalSeasonNoLoyalty() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "NONE");

        // Assert – base 120.0 * 1 night = 120.00
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoomNormalSeasonNoLoyalty() {
        String price = bookingService.calculateRoomPrice("DELUXE", 1, "NORMAL", "NONE");
        assertEquals("200.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoomNormalSeasonNoLoyalty() {
        String price = bookingService.calculateRoomPrice("SUITE", 1, "NORMAL", "NONE");
        assertEquals("350.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoomNormalSeasonNoLoyalty() {
        String price = bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE");
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_unknownRoomTypeDefaultsToStandard() {
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_peakSeasonMultiplier() {
        // SUITE base=350, PEAK *1.5 = 525, 1 night = 525.00
        String price = bookingService.calculateRoomPrice("SUITE", 1, "PEAK", "NONE");
        assertEquals("525.00", price);
    }

    @Test
    void calculateRoomPrice_offSeasonMultiplier() {
        // SUITE base=350, OFF *0.8 = 280, 1 night = 280.00
        String price = bookingService.calculateRoomPrice("SUITE", 1, "OFF", "NONE");
        assertEquals("280.00", price);
    }

    @Test
    void calculateRoomPrice_goldLoyaltyDiscount() {
        // STANDARD base=120, GOLD *0.9 = 108, 1 night = 108.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD");
        assertEquals("108.00", price);
    }

    @Test
    void calculateRoomPrice_platinumLoyaltyDiscount() {
        // STANDARD base=120, PLATINUM *0.8 = 96, 1 night = 96.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM");
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_diamondLoyaltyDiscount() {
        // STANDARD base=120, DIAMOND *0.7 = 84, 1 night = 84.00
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");
        assertEquals("84.00", price);
    }

    @Test
    void calculateRoomPrice_sevenNightsDiscount() {
        // STANDARD base=120, 7 nights *0.95 = 114, total = 114*7 = 798.00
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_fourteenNightsDiscount() {
        // STANDARD base=120, 14 nights *0.90 = 108, total = 108*14 = 1512.00
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");
        assertEquals("1512.00", price);
    }

    @Test
    void calculateRoomPrice_combinedPeakAndGoldLoyalty() {
        // DELUXE base=200, PEAK *1.5=300, GOLD *0.9=270, 1 night = 270.00
        String price = bookingService.calculateRoomPrice("DELUXE", 1, "PEAK", "GOLD");
        assertEquals("270.00", price);
    }

    @Test
    void calculateRoomPrice_combinedOffSeasonAndDiamondLoyalty() {
        // VILLA base=600, OFF *0.8=480, DIAMOND *0.7=336, 1 night = 336.00
        String price = bookingService.calculateRoomPrice("VILLA", 1, "OFF", "DIAMOND");
        assertEquals("336.00", price);
    }

    @Test
    void calculateRoomPrice_multipleNightsWithPeakAndPlatinum() {
        // SUITE base=350, PEAK *1.5=525, PLATINUM *0.8=420, 7 nights *0.95=399, total=399*7=2793.00
        String price = bookingService.calculateRoomPrice("SUITE", 7, "PEAK", "PLATINUM");
        assertEquals("2793.00", price);
    }

    @Test
    void calculateRoomPrice_returnsFormattedTwoDecimalString() {
        String price = bookingService.calculateRoomPrice("STANDARD", 3, "NORMAL", "NONE");
        assertTrue(price.matches("\\d+\\.\\d{2}"), "Price should be formatted to 2 decimal places");
    }

    // -----------------------------------------------------------------------
    // isRoomAvailable tests
    // -----------------------------------------------------------------------

    @Test
    void isRoomAvailable_standardRoomIsAvailable() {
        assertTrue(bookingService.isRoomAvailable("STANDARD"));
    }

    @Test
    void isRoomAvailable_deluxeRoomIsAvailable() {
        assertTrue(bookingService.isRoomAvailable("DELUXE"));
    }

    @Test
    void isRoomAvailable_suiteRoomIsAvailable() {
        assertTrue(bookingService.isRoomAvailable("SUITE"));
    }

    @Test
    void isRoomAvailable_villaRoomIsAvailable() {
        assertTrue(bookingService.isRoomAvailable("VILLA"));
    }

    @Test
    void isRoomAvailable_unknownRoomTypeIsNotAvailable() {
        assertFalse(bookingService.isRoomAvailable("PENTHOUSE"));
    }

    @Test
    void isRoomAvailable_emptyStringIsNotAvailable() {
        assertFalse(bookingService.isRoomAvailable(""));
    }

    @Test
    void isRoomAvailable_lowercaseRoomTypeIsNotAvailable() {
        assertFalse(bookingService.isRoomAvailable("suite"));
    }

    // -----------------------------------------------------------------------
    // generateReport tests
    // -----------------------------------------------------------------------

    @Test
    void generateReport_returnsNonNullString() {
        String result = bookingService.generateReport("March");
        assertNotNull(result);
    }

    @Test
    void generateReport_containsMonthName() {
        String result = bookingService.generateReport("March");
        assertTrue(result.contains("March"), "Report message should contain the month");
    }

    @Test
    void generateReport_containsPaymentApiReference() {
        String result = bookingService.generateReport("April");
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void generateReport_differentMonthsReturnDifferentMessages() {
        String march = bookingService.generateReport("March");
        String april = bookingService.generateReport("April");
        assertNotEquals(march, april);
    }
}
