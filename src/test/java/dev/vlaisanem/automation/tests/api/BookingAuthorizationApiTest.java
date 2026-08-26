package dev.vlaisanem.automation.tests.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIRequestContext;
import dev.vlaisanem.automation.api.BookingClient;
import dev.vlaisanem.automation.core.AutomationTest;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@AutomationTest
@Tag("api")
@Tag("regression")
@Tag("booking")
@Tag("read-only")
@Epic("Booking")
@Feature("Booking access control")
class BookingAuthorizationApiTest {

  @Test
  @DisplayName("Anonymous guest cannot read a booking by id")
  void anonymousCannotReadBooking(APIRequestContext request) {
    assertThat(new BookingClient(request).getBooking(1).status()).isEqualTo(403);
  }

  @Test
  @DisplayName("Anonymous guest cannot list bookings for a room")
  void anonymousCannotListBookings(APIRequestContext request) {
    assertThat(new BookingClient(request).listBookingsForRoom(1).status()).isEqualTo(401);
  }
}
