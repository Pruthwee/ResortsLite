package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @Mock
    private HttpSession session;

    @InjectMocks
    private BookingController bookingController;

    private Map<String, Object> sampleBooking;

    @BeforeEach
    void setUp() {
        sampleBooking = new HashMap<>();
        sampleBooking.put("bookingId", "BK-ABCD1234");
        sampleBooking.put("guestName", "John Smith");
        sampleBooking.put("roomType", "SUITE");
        sampleBooking.put("checkIn", "2024-03-01");
        sampleBooking.put("checkOut", "2024-03-05");
        sampleBooking.put("confirmationCode", "abc123def456");
    }

    // -----------------------------------------------------------------------
    // createBooking tests
    // -----------------------------------------------------------------------

    @Test
    void createBooking_withValidParams_returnsConfirmedStatus() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "John Smith", "SUITE", "2024-03-01", "2024-03-05", session);

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void createBooking_responseContainsBookingObject() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "John Smith", "SUITE", "2024-03-01", "2024-03-05", session);

        // Assert
        assertNotNull(response.get("booking"));
        assertEquals(sampleBooking, response.get("booking"));
    }

    @Test
    void createBooking_setsSessionAttributes() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);

        // Act
        bookingController.createBooking(
                "John Smith", "SUITE", "2024-03-01", "2024-03-05", session);

        // Assert
        verify(session).setAttribute("lastBooking", sampleBooking);
        verify(session).setAttribute("guestName", "John Smith");
    }

    @Test
    void createBooking_callsBookingServiceCreateBooking() {
        // Arrange
        when(bookingService.createBooking("Jane Doe", "DELUXE", "2024-04-01", "2024-04-03"))
                .thenReturn(sampleBooking);

        // Act
        bookingController.createBooking("Jane Doe", "DELUXE", "2024-04-01", "2024-04-03", session);

        // Assert
        verify(bookingService, times(1))
                .createBooking("Jane Doe", "DELUXE", "2024-04-01", "2024-04-03");
    }

    @Test
    void createBooking_responseIsNotNull() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Alice", "VILLA", "2024-05-01", "2024-05-10", session);

        // Assert
        assertNotNull(response);
        assertFalse(response.isEmpty());
    }

    // -----------------------------------------------------------------------
    // getBookingStatus tests
    // -----------------------------------------------------------------------

    @Test
    void getBookingStatus_returnsBookingIdInResponse() {
        // Arrange
        when(session.getAttribute("guestName")).thenReturn("John Smith");
        when(bookingService.getBookingById("BK-ABCD1234")).thenReturn(sampleBooking);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", session);

        // Assert
        assertNotNull(result);
        assertEquals("BK-ABCD1234", result.get("bookingId"));
    }

    @Test
    void getBookingStatus_returnsSessionGuestName() {
        // Arrange
        when(session.getAttribute("guestName")).thenReturn("John Smith");
        when(bookingService.getBookingById(anyString())).thenReturn(sampleBooking);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", session);

        // Assert
        assertEquals("John Smith", result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_returnsDetailsFromService() {
        // Arrange
        when(session.getAttribute("guestName")).thenReturn("John Smith");
        when(bookingService.getBookingById("BK-ABCD1234")).thenReturn(sampleBooking);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", session);

        // Assert
        assertEquals(sampleBooking, result.get("details"));
    }

    @Test
    void getBookingStatus_whenSessionGuestIsNull_returnsNullSessionGuest() {
        // Arrange
        when(session.getAttribute("guestName")).thenReturn(null);
        when(bookingService.getBookingById(anyString())).thenReturn(new HashMap<>());

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-UNKNOWN", session);

        // Assert
        assertNull(result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_callsGetBookingByIdOnService() {
        // Arrange
        when(session.getAttribute("guestName")).thenReturn("Bob");
        when(bookingService.getBookingById("BK-TEST")).thenReturn(new HashMap<>());

        // Act
        bookingController.getBookingStatus("BK-TEST", session);

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-TEST");
    }

    // -----------------------------------------------------------------------
    // checkAvailability tests
    // -----------------------------------------------------------------------

    @Test
    void checkAvailability_returnsRoomTypeInResponse() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("SUITE");

        // Assert
        assertNotNull(result);
        assertEquals("SUITE", result.get("roomType"));
    }

    @Test
    void checkAvailability_whenRoomAvailable_returnsTrue() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("DELUXE");

        // Assert
        assertEquals(true, result.get("available"));
    }

    @Test
    void checkAvailability_whenRoomNotAvailable_returnsFalse() {
        // Arrange
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("PENTHOUSE");

        // Assert
        assertEquals(false, result.get("available"));
    }

    @Test
    void checkAvailability_responseContainsInventoryEndpoint() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> result = bookingController.checkAvailability("VILLA");

        // Assert
        assertNotNull(result.get("inventoryEndpoint"));
        assertTrue(((String) result.get("inventoryEndpoint")).contains("inventory-service"));
    }

    @Test
    void checkAvailability_callsIsRoomAvailableOnService() {
        // Arrange
        when(bookingService.isRoomAvailable("STANDARD")).thenReturn(true);

        // Act
        bookingController.checkAvailability("STANDARD");

        // Assert
        verify(bookingService, times(1)).isRoomAvailable("STANDARD");
    }

    // -----------------------------------------------------------------------
    // downloadReport tests
    // -----------------------------------------------------------------------

    @Test
    void downloadReport_returnsReportPathInResponse() {
        // Arrange
        when(bookingService.generateReport("March")).thenReturn("Report generated for March");

        // Act
        Map<String, Object> result = bookingController.downloadReport("March");

        // Assert
        assertNotNull(result);
        assertNotNull(result.get("reportPath"));
    }

    @Test
    void downloadReport_reportPathContainsMonth() {
        // Arrange
        when(bookingService.generateReport("April")).thenReturn("Report generated for April");

        // Act
        Map<String, Object> result = bookingController.downloadReport("April");

        // Assert
        String reportPath = (String) result.get("reportPath");
        assertTrue(reportPath.contains("April"), "Report path should contain the month");
    }

    @Test
    void downloadReport_responseContainsMessage() {
        // Arrange
        String expectedMessage = "Report generation triggered for: March";
        when(bookingService.generateReport("March")).thenReturn(expectedMessage);

        // Act
        Map<String, Object> result = bookingController.downloadReport("March");

        // Assert
        assertEquals(expectedMessage, result.get("message"));
    }

    @Test
    void downloadReport_callsGenerateReportOnService() {
        // Arrange
        when(bookingService.generateReport("May")).thenReturn("Report for May");

        // Act
        bookingController.downloadReport("May");

        // Assert
        verify(bookingService, times(1)).generateReport("May");
    }

    @Test
    void downloadReport_reportPathEndsWith_bookings_pdf() {
        // Arrange
        when(bookingService.generateReport("June")).thenReturn("Report for June");

        // Act
        Map<String, Object> result = bookingController.downloadReport("June");

        // Assert
        String reportPath = (String) result.get("reportPath");
        assertTrue(reportPath.endsWith("_bookings.pdf"), "Report path should end with _bookings.pdf");
    }
}
