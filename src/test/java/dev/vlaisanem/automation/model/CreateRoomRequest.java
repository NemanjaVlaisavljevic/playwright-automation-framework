package dev.vlaisanem.automation.model;

import java.util.List;

public record CreateRoomRequest(
    String roomName,
    String type,
    boolean accessible,
    String image,
    String description,
    int roomPrice,
    List<String> features) {}
