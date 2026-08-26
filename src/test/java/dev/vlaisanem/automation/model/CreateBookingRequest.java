package dev.vlaisanem.automation.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateBookingRequest(
    @JsonProperty("roomid") int roomId,
    @JsonProperty("firstname") String firstName,
    @JsonProperty("lastname") String lastName,
    @JsonProperty("depositpaid") boolean depositPaid,
    @JsonProperty("bookingdates") BookingDates bookingDates,
    String email,
    String phone) {}
