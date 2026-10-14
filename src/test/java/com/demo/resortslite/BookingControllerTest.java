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
        sampleBooking.put("guestName", "Alice");
        sampleBooking.put("roomType", "SUITE");
        sampleBooking.put("checkIn", "2024-06-01");
        sampleBooking.put("checkOut", "2024-06-05");
        sampleBooking.put("confirmationCode", "abc123");
    }

    // -----------------------------------------------------------------------
    // createBooking tests
    // -----------------------------------------------------------------------

    @Test
    void createBooking_withValidParams_returnsConfirmedStatus() {
        // Arrange
        when(bookingService.createBooking("Alice", "SUITE", "2024-06-01", "2024-06-05"))
                .thenReturn(sampleBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        // Assert
        assertNotNull(response);
        assertEquals("confirmed", response.get("status"));
    }

    @Test
    void createBooking_withValidParams_returnsBookingInResponse() {
        // Arrange
        when(bookingService.createBooking("Alice", "SUITE", "2024-06-01", "2024-06-05"))
                .thenReturn(sampleBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", session);

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
        bookingController.createBooking("Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        // Assert
        verify(session, times(1)).setAttribute("lastBooking", sampleBooking);
        verify(session, times(1)).setAttribute("guestName", "Alice");
    }

    @Test
    void createBooking_callsBookingService() {
        // Arrange
        when(bookingService.createBooking("Bob", "DELUXE", "2024-07-01", "2024-07-03"))
                .thenReturn(sampleBooking);

        // Act
        bookingController.createBooking("Bob", "DELUXE", "2024-07-01", "2024-07-03", session);

        // Assert
        verify(bookingService, times(1))
                .createBooking("Bob", "DELUXE", "2024-07-01", "2024-07-03");
    }

    @Test
    void createBooking_responseContainsTwoKeys() {
        // Arrange
        when(bookingService.createBooking(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(sampleBooking);

        // Act
        Map<String, Object> response = bookingController.createBooking(
                "Alice", "SUITE", "2024-06-01", "2024-06-05", session);

        // Assert
        assertEquals(2, response.size());
    }

    // -----------------------------------------------------------------------
    // getBookingStatus tests
    // -----------------------------------------------------------------------

    @Test
    void getBookingStatus_withValidBookingId_returnsResultMap() {
        // Arrange
        when(session.getAttribute("guestName")).thenReturn("Alice");
        when(bookingService.getBookingById("BK-ABCD1234")).thenReturn(sampleBooking);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", session);

        // Assert
        assertNotNull(result);
        assertEquals("BK-ABCD1234", result.get("bookingId"));
    }

    @Test
    void getBookingStatus_returnsSessionGuest() {
        // Arrange
        when(session.getAttribute("guestName")).thenReturn("Alice");
        when(bookingService.getBookingById(anyString())).thenReturn(sampleBooking);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", session);

        // Assert
        assertEquals("Alice", result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_whenSessionGuestIsNull_returnsNullSessionGuest() {
        // Arrange
        when(session.getAttribute("guestName")).thenReturn(null);
        when(bookingService.getBookingById(anyString())).thenReturn(sampleBooking);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", session);

        // Assert
        assertNull(result.get("sessionGuest"));
    }

    @Test
    void getBookingStatus_returnsDetailsFromService() {
        // Arrange
        when(session.getAttribute("guestName")).thenReturn("Alice");
        when(bookingService.getBookingById("BK-ABCD1234")).thenReturn(sampleBooking);

        // Act
        Map<String, Object> result = bookingController.getBookingStatus("BK-ABCD1234", session);

        // Assert
        assertEquals(sampleBooking, result.get("details"));
    }

    @Test
    void getBookingStatus_callsBookingServiceGetById() {
        // Arrange
        when(session.getAttribute("guestName")).thenReturn("Alice");
        when(bookingService.getBookingById("BK-XYZ")).thenReturn(new HashMap<>());

        // Act
        bookingController.getBookingStatus("BK-XYZ", session);

        // Assert
        verify(bookingService, times(1)).getBookingById("BK-XYZ");
    }

    // -----------------------------------------------------------------------
    // checkAvailability tests
    // -----------------------------------------------------------------------

    @Test
    void checkAvailability_withAvailableRoom_returnsAvailableTrue() {
        // Arrange
        when(bookingService.isRoomAvailable("SUITE")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("SUITE");

        // Assert
        assertNotNull(response);
        assertEquals(true, response.get("available"));
    }

    @Test
    void checkAvailability_withUnavailableRoom_returnsAvailableFalse() {
        // Arrange
        when(bookingService.isRoomAvailable("PENTHOUSE")).thenReturn(false);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("PENTHOUSE");

        // Assert
        assertEquals(false, response.get("available"));
    }

    @Test
    void checkAvailability_returnsRoomTypeInResponse() {
        // Arrange
        when(bookingService.isRoomAvailable("DELUXE")).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("DELUXE");

        // Assert
        assertEquals("DELUXE", response.get("roomType"));
    }

    @Test
    void checkAvailability_returnsInventoryEndpoint() {
        // Arrange
        when(bookingService.isRoomAvailable(anyString())).thenReturn(true);

        // Act
        Map<String, Object> response = bookingController.checkAvailability("STANDARD");

        // Assert
        assertNotNull(response.get("inventoryEndpoint"));
        assertTrue(response.get("inventoryEndpoint").toString().contains("inventory-service"));
    }

    @Test
    void checkAvailability_callsIsRoomAvailable() {
        // Arrange
        when(bookingService.isRoomAvailable("VILLA")).thenReturn(true);

        // Act
        bookingController.checkAvailability("VILLA");

        // Assert
        verify(bookingService, times(1)).isRoomAvailable("VILLA");
    }

    // -----------------------------------------------------------------------
    // downloadReport tests
    // -----------------------------------------------------------------------

    @Test
    void downloadReport_returnsReportPathContainingMonth() {
        // Arrange
        when(bookingService.generateReport("March")).thenReturn("Report for March");

        // Act
        Map<String, Object> response = bookingController.downloadReport("March");

        // Assert
        assertNotNull(response);
        assertTrue(response.get("reportPath").toString().contains("March"));
    }

    @Test
    void downloadReport_returnsMessageFromService() {
        // Arrange
        when(bookingService.generateReport("April")).thenReturn("Report for April");

        // Act
        Map<String, Object> response = bookingController.downloadReport("April");

        // Assert
        assertEquals("Report for April", response.get("message"));
    }

    @Test
    void downloadReport_reportPathEndsWithPdf() {
        // Arrange
        when(bookingService.generateReport("June")).thenReturn("Report for June");

        // Act
        Map<String, Object> response = bookingController.downloadReport("June");

        // Assert
        assertTrue(response.get("reportPath").toString().endsWith(".pdf"));
    }

    @Test
    void downloadReport_callsGenerateReport() {
        // Arrange
        when(bookingService.generateReport("July")).thenReturn("Report for July");

        // Act
        bookingController.downloadReport("July");

        // Assert
        verify(bookingService, times(1)).generateReport("July");
    }

    @Test
    void downloadReport_responseContainsTwoKeys() {
        // Arrange
        when(bookingService.generateReport(anyString())).thenReturn("Report generated");

        // Act
        Map<String, Object> response = bookingController.downloadReport("August");

        // Assert
        assertEquals(2, response.size());
    }
}
