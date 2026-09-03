package dev.vlaisanem.automation.tests.ui;

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
import dev.vlaisanem.automation.model.ValidationErrorResponse;
import dev.vlaisanem.automation.ui.pages.RoomReservationPage;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * A guest-facing counterpart to {@code BookingWriteProtectionApiTest}'s "missing guest name is
 * rejected" case, exercised through the actual reservation UI instead of a raw API call.
 */
@AutomationTest
@Tag("ui")
@Tag("regression")
@Tag("booking")
@Tag("mutation")
@ResourceLock("restful-booker-platform-mutations")
@Epic("Guest experience")
@Feature("Booking journey")
class BookingValidationUiTest {

  @Test
  @DisplayName("Guest cannot complete a booking with an empty guest name")
  void guestCannotBookWithEmptyName(
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
    BookingClient bookings = new BookingClient(adminRequest);

    try (ManagedRoom room =
        steps.call("Provision an available room", () -> ManagedRoom.create(rooms))) {
      LocalDate checkin = LocalDate.now().plusDays(180);
      LocalDate checkout = checkin.plusDays(1);
      RoomReservationPage reservation =
          steps.call(
              "Open booking page for selected stay",
              () ->
                  RoomReservationPage.openFor(page, room.roomId(), checkin, checkout)
                      .startReservation());

      steps.run(
          "Enter guest details with an empty name",
          () -> reservation.fillGuestDetails("", "", "portfolio.guest@example.com", "07123456789"));

      // Empirically confirmed against the running app (and its source,
      // assets/src/components/reservation/BookingForm.tsx): the reservation form does not block an
      // empty guest name client-side - the request always reaches the server and is rejected there
      // (400, with the same missing-name validation errors BookingWriteProtectionApiTest already
      // documents at the API level). submitBooking() does not tolerate a missing response - a
      // genuine interaction failure (e.g. the click not firing a request at all) must fail this
      // test, not be silently read as "validation worked".
      ApiResult response = steps.call("Submit the booking", reservation::submitBooking);

      // Guards against a validation regression that starts accepting this (201): the same
      // pattern BookingWriteProtectionApiTest already uses for its API-level equivalent. If the
      // assertion below ever fails because the server actually created a booking, that booking is
      // still cleaned up rather than left behind - trackIfCreated returns null here for the
      // expected 400 case, so close() on a null-tracked resource is simply a no-op.
      try (ManagedBooking unexpected = ManagedBooking.trackIfCreated(bookings, response)) {
        steps.run(
            "Verify booking rejected",
            () -> {
              assertThat(response.status()).isEqualTo(400);
              assertThat(unexpected).isNull();
              assertThat(response.bodyAs(ValidationErrorResponse.class).errors())
                  .contains("Firstname should not be blank", "Lastname should not be blank");
              reservation.assertBookingNotConfirmed();
              reservation.assertValidationErrors(
                  "Firstname should not be blank", "Lastname should not be blank");
            });
      }
    }
  }
}
