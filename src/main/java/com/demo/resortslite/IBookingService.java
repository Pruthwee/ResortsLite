package com.demo.resortslite;

import java.util.Map;

/**
 * Booking service interface for loose coupling
 * Enables independent deployment and service-to-service communication
 */
public interface IBookingService {
    
    Map<String, Object> createBooking(String guestName, String roomType, String checkIn, String checkOut);
    
    Map<String, Object> getBookingById(String bookingId);
    
    String calculateRoomPrice(String roomType, int nights, String season, String loyalty);
    
    boolean isRoomAvailable(String roomType);
    
    String generateReport(String month);
}
