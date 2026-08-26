package dev.vlaisanem.automation.tests.journey;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Page;
import dev.vlaisanem.automation.api.ApiContextFactory;
import dev.vlaisanem.automation.api.ApiResult;
import dev.vlaisanem.automation.api.AuthClient;
import dev.vlaisanem.automation.api.BookingClient;
import dev.vlaisanem.automation.api.RoomClient;
import dev.vlaisanem.automation.config.TestConfig;
import dev.vlaisanem.automation.core.AutomationTest;
import dev.vlaisanem.automation.data.ManagedBooking;
import dev.vlaisanem.automation.data.ManagedRoom;
import dev.vlaisanem.automation.model.AuthCredentials;
import dev.vlaisanem.automation.model.BookingResponse;
import dev.vlaisanem.automation.ui.pages.RoomReservationPage;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@AutomationTest
@Tag("journey")
@Tag("regression")
@Tag("booking")
@Tag("mutation")
@ResourceLock("restful-booker-platform-mutations")
@Epic("Guest experience")
@Feature("Booking journey")
class BookingJourneyTest {

  @Test
  @DisplayName("Guest can complete a booking for a freshly created, isolated room")
  void guestCanCompleteBooking(
      Page page, APIRequestContext request, ApiContextFactory apiContexts, TestConfig config) {
    String token =
        new AuthClient(request)
            .loginAndReadToken(new AuthCredentials(config.adminUsername(), config.adminPassword()))
            .token();
    APIRequestContext adminRequest = apiContexts.withCookie("token", token);
    RoomClient rooms = new RoomClient(adminRequest);
    BookingClient cleanupBookings = new BookingClient(adminRequest);

    try (ManagedRoom room = ManagedRoom.create(rooms)) {
      // The calendar widget's day cells do not affect what gets submitted, so this navigates
      // straight to the reservation page with a date far from "today" — combined with a
      // room this test just created, no other guest of this shared public target can collide
      // with it.
      LocalDate checkin = LocalDate.now().plusDays(180);
      LocalDate checkout = checkin.plusDays(1);
      RoomReservationPage reservation =
          RoomReservationPage.openFor(page, room.roomId(), checkin, checkout)
              .startReservation()
              .fillGuestDetails("Portfolio", "Guest", "portfolio.guest@example.com", "07123456789");

      ApiResult submission = reservation.submitBooking();
      try (ManagedBooking booking = ManagedBooking.trackIfCreated(cleanupBookings, submission)) {
        assertThat(submission.status()).isEqualTo(201);
        assertThat(booking).isNotNull();
        BookingResponse created = submission.bodyAs(BookingResponse.class);
        assertThat(created.roomId()).isEqualTo(room.roomId());
        reservation.assertBookingConfirmed();
      }
    }
  }
}
