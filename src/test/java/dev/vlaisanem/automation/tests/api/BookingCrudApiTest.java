package dev.vlaisanem.automation.tests.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIRequestContext;
import dev.vlaisanem.automation.api.ApiContextFactory;
import dev.vlaisanem.automation.api.ApiResult;
import dev.vlaisanem.automation.api.AuthClient;
import dev.vlaisanem.automation.api.BookingClient;
import dev.vlaisanem.automation.api.RoomClient;
import dev.vlaisanem.automation.config.TestConfig;
import dev.vlaisanem.automation.core.AutomationTest;
import dev.vlaisanem.automation.core.Steps;
import dev.vlaisanem.automation.data.BookingTestData;
import dev.vlaisanem.automation.data.ManagedBooking;
import dev.vlaisanem.automation.data.ManagedRoom;
import dev.vlaisanem.automation.model.AuthCredentials;
import dev.vlaisanem.automation.model.BookingDates;
import dev.vlaisanem.automation.model.BookingResponse;
import dev.vlaisanem.automation.model.CreateBookingRequest;
import dev.vlaisanem.automation.model.UpdatedBookingResponse;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@AutomationTest
@Tag("api")
@Tag("regression")
@Tag("booking")
@Tag("mutation")
@ResourceLock("restful-booker-platform-mutations")
@Epic("Booking")
@Feature("Booking lifecycle")
class BookingCrudApiTest {

  @Test
  @DisplayName("Admin can create, read, update, and delete an isolated booking")
  void adminCanManageBookingLifecycle(
      APIRequestContext request, ApiContextFactory apiContexts, TestConfig config, Steps steps) {
    AuthClient auth = new AuthClient(request);
    String token =
        steps.call(
            "Authenticate as admin",
            () ->
                auth.loginAndReadToken(
                        new AuthCredentials(config.adminUsername(), config.adminPassword()))
                    .token());
    BookingClient anonymousBookings = new BookingClient(request);
    APIRequestContext adminRequest = apiContexts.withCookie("token", token);
    BookingClient authBookings = new BookingClient(adminRequest);
    RoomClient rooms = new RoomClient(adminRequest);

    try (ManagedRoom room =
        steps.call("Provision an available room", () -> ManagedRoom.create(rooms))) {
      CreateBookingRequest requested = BookingTestData.uniqueBooking(room.roomId());
      try (ManagedBooking booking =
          steps.call(
              "Provision an existing booking",
              () -> ManagedBooking.create(anonymousBookings, authBookings, requested))) {
        steps.run(
            "Verify created booking",
            () -> {
              BookingResponse created = booking.created();
              assertThat(created.roomId()).isEqualTo(room.roomId());
              assertThat(created.lastName()).isEqualTo(requested.lastName());
              assertThat(created.bookingDates()).isEqualTo(requested.bookingDates());
            });

        ApiResult read =
            steps.call("Read the booking", () -> authBookings.getBooking(booking.bookingId()));
        steps.run(
            "Verify read booking",
            () -> {
              assertThat(read.status()).isEqualTo(200);
              assertThat(read.bodyAs(BookingResponse.class).firstName())
                  .isEqualTo(requested.firstName());
            });

        BookingDates newDates =
            new BookingDates(
                requested.bookingDates().checkin().plusDays(10),
                requested.bookingDates().checkout().plusDays(10));
        CreateBookingRequest updateRequest =
            new CreateBookingRequest(
                requested.roomId(),
                requested.firstName(),
                "Updated-" + requested.lastName(),
                requested.depositPaid(),
                newDates,
                requested.email(),
                requested.phone());

        ApiResult update =
            steps.call(
                "Update the booking",
                () -> authBookings.updateBooking(booking.bookingId(), updateRequest));
        steps.run(
            "Verify updated booking",
            () -> {
              assertThat(update.status()).isEqualTo(200);
              UpdatedBookingResponse updated = update.bodyAs(UpdatedBookingResponse.class);
              assertThat(updated.booking().lastName()).isEqualTo(updateRequest.lastName());
              assertThat(updated.booking().bookingDates()).isEqualTo(newDates);
            });

        steps.run(
            "Verify updated booking persists on re-read",
            () ->
                assertThat(
                        authBookings
                            .getBooking(booking.bookingId())
                            .bodyAs(BookingResponse.class)
                            .lastName())
                    .isEqualTo(updateRequest.lastName()));
      }
    }
  }
}
