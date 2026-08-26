package dev.vlaisanem.automation.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UpdatedBookingResponse(
    @JsonProperty("bookingid") int bookingId, BookingResponse booking) {}
