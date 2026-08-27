package dev.vlaisanem.automation.model;

import java.time.LocalDate;

public record ReportEvent(String title, LocalDate start, LocalDate end) {}
