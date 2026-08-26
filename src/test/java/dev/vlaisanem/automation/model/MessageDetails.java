package dev.vlaisanem.automation.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MessageDetails(
    @JsonProperty("messageid") int messageId,
    String name,
    String email,
    String phone,
    String subject,
    String description) {}
