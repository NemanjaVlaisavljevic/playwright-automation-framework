package dev.vlaisanem.automation.model;

public record MessageRequest(
    String name, String email, String phone, String subject, String description) {}
