package dev.vlaisanem.automation.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record BookingResponse(
    @JsonProperty("bookingid") int bookingId,
    @JsonProperty("roomid") int roomId,
    @JsonProperty("firstname") String firstName,
    @JsonProperty("lastname") String lastName,
    @JsonProperty("depositpaid") boolean depositPaid,
    @JsonProperty("bookingdates") BookingDates bookingDates) {}
