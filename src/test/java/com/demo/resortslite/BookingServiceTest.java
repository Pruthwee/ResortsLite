package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

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
    void createBooking_withValidInputs_returnsBookingMap() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05");

        // Assert
        assertNotNull(result);
        assertEquals("Alice", result.get("guestName"));
        assertEquals("SUITE", result.get("roomType"));
        assertEquals("2024-06-01", result.get("checkIn"));
        assertEquals("2024-06-05", result.get("checkOut"));
        assertNotNull(result.get("bookingId"));
        assertNotNull(result.get("confirmationCode"));
    }

    @Test
    void createBooking_bookingIdStartsWithBK() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Bob", "DELUXE", "2024-07-01", "2024-07-03");

        // Assert
        String bookingId = (String) result.get("bookingId");
        assertTrue(bookingId.startsWith("BK-"), "Booking ID should start with BK-");
    }

    @Test
    void createBooking_confirmationCodeIsNotEmpty() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Carol", "STANDARD", "2024-08-10", "2024-08-12");

        // Assert
        String confirmCode = (String) result.get("confirmationCode");
        assertNotNull(confirmCode);
        assertFalse(confirmCode.isEmpty());
    }

    @Test
    void createBooking_containsDbHost() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        Map<String, Object> result = bookingService.createBooking(
                "Dave", "VILLA", "2024-09-01", "2024-09-07");

        // Assert
        assertNotNull(result.get("dbHost"));
    }

    @Test
    void createBooking_jdbcTemplateExecuteIsCalled() {
        // Arrange
        doNothing().when(jdbcTemplate).execute(anyString());

        // Act
        bookingService.createBooking("Eve", "SUITE", "2024-10-01", "2024-10-04");

        // Assert
        verify(jdbcTemplate, times(1)).execute(anyString());
    }

    // -----------------------------------------------------------------------
    // getBookingById tests
    // -----------------------------------------------------------------------

    @Test
    void getBookingById_whenFound_returnsResult() {
        // Arrange
        Map<String, Object> dbRow = Map.of("id", "BK-ABCD1234", "guest", "Alice");
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(dbRow);

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-ABCD1234");

        // Assert
        assertNotNull(result);
        assertEquals("Alice", result.get("guest"));
    }

    @Test
    void getBookingById_whenNotFound_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString()))
                .thenThrow(new RuntimeException("No rows found"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("BK-UNKNOWN");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("BK-UNKNOWN"));
    }

    @Test
    void getBookingById_withEmptyId_returnsErrorMap() {
        // Arrange
        when(jdbcTemplate.queryForMap(anyString()))
                .thenThrow(new RuntimeException("Empty id"));

        // Act
        Map<String, Object> result = bookingService.getBookingById("");

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("error"));
    }

    // -----------------------------------------------------------------------
    // calculateRoomPrice tests
    // -----------------------------------------------------------------------

    @Test
    void calculateRoomPrice_standardRoomNormalSeason_returnsCorrectPrice() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 3, "NORMAL", "NONE");

        // Assert
        assertEquals("360.00", price);
    }

    @Test
    void calculateRoomPrice_deluxeRoomPeakSeason_returnsCorrectPrice() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("DELUXE", 2, "PEAK", "NONE");

        // Assert
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_suiteRoomOffSeason_returnsCorrectPrice() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("SUITE", 1, "OFF", "NONE");

        // Assert
        assertEquals("280.00", price);
    }

    @Test
    void calculateRoomPrice_villaRoomNormalSeason_returnsCorrectPrice() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("VILLA", 1, "NORMAL", "NONE");

        // Assert
        assertEquals("600.00", price);
    }

    @Test
    void calculateRoomPrice_unknownRoomType_usesDefaultBasePrice() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("UNKNOWN", 1, "NORMAL", "NONE");

        // Assert
        assertEquals("120.00", price);
    }

    @Test
    void calculateRoomPrice_goldLoyalty_appliesDiscount() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "GOLD");

        // Assert
        assertEquals("108.00", price);
    }

    @Test
    void calculateRoomPrice_platinumLoyalty_appliesDiscount() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "PLATINUM");

        // Assert
        assertEquals("96.00", price);
    }

    @Test
    void calculateRoomPrice_diamondLoyalty_appliesDiscount() {
        // Arrange / Act
        String price = bookingService.calculateRoomPrice("STANDARD", 1, "NORMAL", "DIAMOND");

        // Assert
        assertEquals("84.00", price);
    }

    @Test
    void calculateRoomPrice_sevenNights_appliesWeeklyDiscount() {
        // Arrange / Act
        // STANDARD 120 * 0.95 * 7 = 798.00
        String price = bookingService.calculateRoomPrice("STANDARD", 7, "NORMAL", "NONE");

        // Assert
        assertEquals("798.00", price);
    }

    @Test
    void calculateRoomPrice_fourteenNights_appliesTwoWeekDiscount() {
        // Arrange / Act
        // STANDARD 120 * 0.95 (nights>=7 branch fires first) * 14 = 1596.00
        String price = bookingService.calculateRoomPrice("STANDARD", 14, "NORMAL", "NONE");

        // Assert
        assertEquals("1596.00", price);
    }

    @Test
    void calculateRoomPrice_peakSeasonGoldLoyalty_combinesMultipliers() {
        // Arrange / Act
        // DELUXE 200 * 1.5 (PEAK) * 0.9 (GOLD) * 2 nights = 540.00
        String price = bookingService.calculateRoomPrice("DELUXE", 2, "PEAK", "GOLD");

        // Assert
        assertEquals("540.00", price);
    }

    // -----------------------------------------------------------------------
    // isRoomAvailable tests
    // -----------------------------------------------------------------------

    @Test
    void isRoomAvailable_standardRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("STANDARD"));
    }

    @Test
    void isRoomAvailable_deluxeRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("DELUXE"));
    }

    @Test
    void isRoomAvailable_suiteRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("SUITE"));
    }

    @Test
    void isRoomAvailable_villaRoom_returnsTrue() {
        assertTrue(bookingService.isRoomAvailable("VILLA"));
    }

    @Test
    void isRoomAvailable_unknownRoomType_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("PENTHOUSE"));
    }

    @Test
    void isRoomAvailable_emptyString_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable(""));
    }

    @Test
    void isRoomAvailable_nullLikeString_returnsFalse() {
        assertFalse(bookingService.isRoomAvailable("null"));
    }

    // -----------------------------------------------------------------------
    // generateReport tests
    // -----------------------------------------------------------------------

    @Test
    void generateReport_returnsStringContainingMonth() {
        // Act
        String result = bookingService.generateReport("March");

        // Assert
        assertNotNull(result);
        assertTrue(result.contains("March"));
    }

    @Test
    void generateReport_returnsNonEmptyString() {
        // Act
        String result = bookingService.generateReport("January");

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void generateReport_containsPaymentApiReference() {
        // Act
        String result = bookingService.generateReport("April");

        // Assert
        assertTrue(result.contains("via"));
    }
}
