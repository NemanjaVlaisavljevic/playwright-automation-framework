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
import dev.vlaisanem.automation.core.Steps;
import dev.vlaisanem.automation.data.ManagedBooking;
import dev.vlaisanem.automation.data.ManagedRoom;
import dev.vlaisanem.automation.model.AuthCredentials;
import dev.vlaisanem.automation.model.BookingDates;
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
      Page page,
      APIRequestContext request,
      ApiContextFactory apiContexts,
      TestConfig config,
      Steps steps) {
    String token =
        steps.call(
            "Authenticate as admin",
            () ->
                new AuthClient(request)
                    .loginAndReadToken(
                        new AuthCredentials(config.adminUsername(), config.adminPassword()))
                    .token());
    APIRequestContext adminRequest = apiContexts.withCookie("token", token);
    RoomClient rooms = new RoomClient(adminRequest);
    BookingClient cleanupBookings = new BookingClient(adminRequest);

    try (ManagedRoom room =
        steps.call("Provision an available room", () -> ManagedRoom.create(rooms))) {
      // The calendar widget's day cells do not affect what gets submitted, so this navigates
      // straight to the reservation page with a date far from "today" — combined with a
      // room this test just created, no other guest of this shared public target can collide
      // with it.
      LocalDate checkin = LocalDate.now().plusDays(180);
      LocalDate checkout = checkin.plusDays(1);

      RoomReservationPage reservation =
          steps.call(
              "Open booking page for selected stay",
              () ->
                  RoomReservationPage.openFor(page, room.roomId(), checkin, checkout)
                      .startReservation());

      String guestFirstName = "Portfolio";
      String guestLastName = "Guest";
      steps.run(
          "Enter guest details",
          () ->
              reservation.fillGuestDetails(
                  guestFirstName, guestLastName, "portfolio.guest@example.com", "07123456789"));

      ApiResult submission = steps.call("Submit the booking", reservation::submitBooking);

      try (ManagedBooking booking = ManagedBooking.trackIfCreated(cleanupBookings, submission)) {
        steps.run(
            "Verify booking confirmation",
            () -> {
              assertThat(submission.status()).isEqualTo(201);
              assertThat(booking).isNotNull();
              BookingResponse created = submission.bodyAs(BookingResponse.class);
              assertThat(created.roomId()).isEqualTo(room.roomId());
              reservation.assertBookingConfirmed();
            });

        steps.run(
            "Verify persisted booking through API",
            () -> {
              ApiResult persisted = cleanupBookings.getBooking(booking.bookingId());
              assertThat(persisted.status()).isEqualTo(200);
              BookingResponse fetched = persisted.bodyAs(BookingResponse.class);
              assertThat(fetched.roomId()).isEqualTo(room.roomId());
              assertThat(fetched.firstName()).isEqualTo(guestFirstName);
              assertThat(fetched.lastName()).isEqualTo(guestLastName);
              assertThat(fetched.bookingDates()).isEqualTo(new BookingDates(checkin, checkout));
            });
      }
    }
  }
}
