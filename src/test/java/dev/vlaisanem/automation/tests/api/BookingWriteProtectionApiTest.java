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
import dev.vlaisanem.automation.data.BookingTestData;
import dev.vlaisanem.automation.data.ManagedBooking;
import dev.vlaisanem.automation.data.ManagedRoom;
import dev.vlaisanem.automation.model.AuthCredentials;
import dev.vlaisanem.automation.model.CreateBookingRequest;
import dev.vlaisanem.automation.model.ValidationErrorResponse;
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
@Feature("Booking write protection")
class BookingWriteProtectionApiTest {

  @Test
  @DisplayName("Anonymous guest cannot update a test-owned booking")
  void anonymousCannotUpdateBooking(
      APIRequestContext request, ApiContextFactory apiContexts, TestConfig config) {
    TestClients clients = clients(request, apiContexts, config);

    try (ManagedRoom room = ManagedRoom.create(clients.rooms())) {
      CreateBookingRequest original = BookingTestData.uniqueBooking(room.roomId());
      try (ManagedBooking booking =
          ManagedBooking.create(clients.anonymousBookings(), clients.adminBookings(), original)) {
        CreateBookingRequest attemptedUpdate = BookingTestData.uniqueBooking(room.roomId());

        assertThat(
                clients
                    .anonymousBookings()
                    .updateBooking(booking.bookingId(), attemptedUpdate)
                    .status())
            .isEqualTo(403);
      }
    }
  }

  @Test
  @DisplayName("Anonymous guest cannot delete a test-owned booking")
  void anonymousCannotDeleteBooking(
      APIRequestContext request, ApiContextFactory apiContexts, TestConfig config) {
    TestClients clients = clients(request, apiContexts, config);

    try (ManagedRoom room = ManagedRoom.create(clients.rooms())) {
      try (ManagedBooking booking =
          ManagedBooking.create(
              clients.anonymousBookings(),
              clients.adminBookings(),
              BookingTestData.uniqueBooking(room.roomId()))) {
        assertThat(clients.anonymousBookings().deleteBooking(booking.bookingId()).status())
            .isEqualTo(403);
      }
    }
  }

  @Test
  @DisplayName("Creating a booking without a guest name is rejected")
  void createRejectsMissingGuestName(
      APIRequestContext request, ApiContextFactory apiContexts, TestConfig config) {
    TestClients clients = clients(request, apiContexts, config);

    try (ManagedRoom room = ManagedRoom.create(clients.rooms())) {
      CreateBookingRequest valid = BookingTestData.uniqueBooking(room.roomId());
      CreateBookingRequest missingName =
          new CreateBookingRequest(
              valid.roomId(),
              "",
              "",
              valid.depositPaid(),
              valid.bookingDates(),
              valid.email(),
              valid.phone());
      ApiResult response = clients.anonymousBookings().createBooking(missingName);

      try (ManagedBooking unexpected =
          ManagedBooking.trackIfCreated(clients.adminBookings(), response)) {
        assertThat(response.status()).isEqualTo(400);
        assertThat(unexpected).isNull();
        assertThat(response.bodyAs(ValidationErrorResponse.class).errors())
            .contains("Firstname should not be blank", "Lastname should not be blank");
      }
    }
  }

  private static TestClients clients(
      APIRequestContext request, ApiContextFactory apiContexts, TestConfig config) {
    String token =
        new AuthClient(request)
            .loginAndReadToken(new AuthCredentials(config.adminUsername(), config.adminPassword()))
            .token();
    APIRequestContext adminRequest = apiContexts.withCookie("token", token);
    return new TestClients(
        new BookingClient(request), new BookingClient(adminRequest), new RoomClient(adminRequest));
  }

  private record TestClients(
      BookingClient anonymousBookings, BookingClient adminBookings, RoomClient rooms) {}
}
