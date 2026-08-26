package dev.vlaisanem.automation.data;

import dev.vlaisanem.automation.model.BookingDates;
import dev.vlaisanem.automation.model.CreateBookingRequest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

public final class BookingTestData {
  private BookingTestData() {}

  public static CreateBookingRequest uniqueBooking(int roomId) {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    LocalDate checkin = LocalDate.now(ZoneOffset.UTC).plusDays(60);
    LocalDate checkout = checkin.plusDays(1);
    return new CreateBookingRequest(
        roomId,
        "Portfolio",
        "Guest-" + suffix,
        false,
        new BookingDates(checkin, checkout),
        "portfolio.guest@example.com",
        "07123456789");
  }
}
