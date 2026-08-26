package dev.vlaisanem.automation.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record Room(
    @JsonProperty("roomid") int roomId,
    String roomName,
    String type,
    boolean accessible,
    String image,
    String description,
    int roomPrice,
    List<String> features) {}
