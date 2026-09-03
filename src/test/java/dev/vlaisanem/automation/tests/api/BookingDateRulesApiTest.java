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
import dev.vlaisanem.automation.data.ManagedBooking;
import dev.vlaisanem.automation.data.ManagedRoom;
import dev.vlaisanem.automation.model.AuthCredentials;
import dev.vlaisanem.automation.model.BookingDates;
import dev.vlaisanem.automation.model.BookingFailureResponse;
import dev.vlaisanem.automation.model.CreateBookingRequest;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
@Feature("Booking date rules")
class BookingDateRulesApiTest {

  private static final int FUTURE_OFFSET_DAYS = 120;

  @Test
  @DisplayName("A checkout date before the checkin date is rejected")
  void checkoutBeforeCheckinIsRejected(
      APIRequestContext request, ApiContextFactory apiContexts, TestConfig config, Steps steps) {
    TestClients clients =
        steps.call("Authenticate as admin", () -> clients(request, apiContexts, config));

    try (ManagedRoom room =
        steps.call("Provision an available room", () -> ManagedRoom.create(clients.rooms()))) {
      LocalDate checkout = futureDate(0);
      LocalDate checkin = checkout.plusDays(5);
      ApiResult response =
          steps.call(
              "Attempt a booking with checkout before checkin",
              () ->
                  clients
                      .anonymousBookings()
                      .createBooking(booking(room.roomId(), checkin, checkout)));

      steps.run(
          "Verify booking rejected",
          () -> assertRejectedAndCleanUpIfNecessary(response, clients.adminBookings()));
      steps.run(
          "Verify rejection reason",
          () ->
              assertThat(response.bodyAs(BookingFailureResponse.class).error())
                  .isEqualTo("Failed to create booking"));
    }
  }

  @Test
  @DisplayName("A zero-night stay is rejected")
  void zeroNightStayIsRejected(
      APIRequestContext request, ApiContextFactory apiContexts, TestConfig config, Steps steps) {
    TestClients clients =
        steps.call("Authenticate as admin", () -> clients(request, apiContexts, config));

    try (ManagedRoom room =
        steps.call("Provision an available room", () -> ManagedRoom.create(clients.rooms()))) {
      LocalDate sameDay = futureDate(10);
      ApiResult response =
          steps.call(
              "Attempt a zero-night booking",
              () ->
                  clients
                      .anonymousBookings()
                      .createBooking(booking(room.roomId(), sameDay, sameDay)));

      steps.run(
          "Verify booking rejected",
          () -> assertRejectedAndCleanUpIfNecessary(response, clients.adminBookings()));
    }
  }

  @Test
  @DisplayName(
      "Known gap: the API accepts a booking dated entirely in the past instead of rejecting it")
  @Tag("known-defect")
  void pastDatedBookingIsAcceptedInsteadOfRejected(
      APIRequestContext request, ApiContextFactory apiContexts, TestConfig config, Steps steps) {
    TestClients clients =
        steps.call("Authenticate as admin", () -> clients(request, apiContexts, config));

    try (ManagedRoom room =
        steps.call("Provision an available room", () -> ManagedRoom.create(clients.rooms()))) {
      LocalDate checkin = LocalDate.now(ZoneOffset.UTC).minusYears(5);
      CreateBookingRequest pastBooking = booking(room.roomId(), checkin, checkin.plusDays(1));
      ApiResult response =
          steps.call(
              "Create a booking dated entirely in the past",
              () -> clients.anonymousBookings().createBooking(pastBooking));

      try (ManagedBooking created =
          ManagedBooking.trackIfCreated(clients.adminBookings(), response)) {
        steps.run(
            "Verify booking was accepted instead of rejected (known gap)",
            () -> {
              assertThat(response.status()).isEqualTo(201);
              assertThat(created).isNotNull();
            });
      }
    }
  }

  @Test
  @DisplayName("Overlapping date ranges for the same room are rejected")
  void overlappingBookingsAreRejected(
      APIRequestContext request, ApiContextFactory apiContexts, TestConfig config, Steps steps) {
    TestClients clients =
        steps.call("Authenticate as admin", () -> clients(request, apiContexts, config));

    try (ManagedRoom room =
        steps.call("Provision an available room", () -> ManagedRoom.create(clients.rooms()))) {
      LocalDate originalCheckin = futureDate(20);
      CreateBookingRequest original =
          booking(room.roomId(), originalCheckin, originalCheckin.plusDays(5));
      try (ManagedBooking existing =
          steps.call(
              "Provision an existing booking",
              () ->
                  ManagedBooking.create(
                      clients.anonymousBookings(), clients.adminBookings(), original))) {
        steps.run(
            "Verify existing booking created", () -> assertThat(existing.bookingId()).isPositive());

        ApiResult exactOverlap =
            steps.call(
                "Attempt an exact-overlap booking",
                () -> clients.anonymousBookings().createBooking(original));
        steps.run(
            "Verify exact overlap rejected",
            () -> assertRejectedAndCleanUpIfNecessary(exactOverlap, clients.adminBookings()));

        ApiResult partialOverlap =
            steps.call(
                "Attempt a partial-overlap booking",
                () ->
                    clients
                        .anonymousBookings()
                        .createBooking(
                            booking(
                                room.roomId(),
                                originalCheckin.plusDays(2),
                                originalCheckin.plusDays(8))));

        steps.run(
            "Verify partial overlap rejected",
            () -> assertRejectedAndCleanUpIfNecessary(partialOverlap, clients.adminBookings()));
      }
    }
  }

  @Test
  @DisplayName("Adjacent bookings sharing a checkout/checkin boundary day are both accepted")
  void adjacentBookingsShareBoundaryDay(
      APIRequestContext request, ApiContextFactory apiContexts, TestConfig config, Steps steps) {
    TestClients clients =
        steps.call("Authenticate as admin", () -> clients(request, apiContexts, config));

    try (ManagedRoom room =
        steps.call("Provision an available room", () -> ManagedRoom.create(clients.rooms()))) {
      LocalDate firstCheckin = futureDate(40);
      CreateBookingRequest first = booking(room.roomId(), firstCheckin, firstCheckin.plusDays(5));
      try (ManagedBooking firstBooking =
          steps.call(
              "Provision the first booking",
              () ->
                  ManagedBooking.create(
                      clients.anonymousBookings(), clients.adminBookings(), first))) {
        steps.run(
            "Verify first booking created",
            () -> assertThat(firstBooking.bookingId()).isPositive());

        CreateBookingRequest adjacent =
            booking(room.roomId(), firstCheckin.plusDays(5), firstCheckin.plusDays(10));
        ApiResult adjacentResponse =
            steps.call(
                "Create the adjacent booking",
                () -> clients.anonymousBookings().createBooking(adjacent));

        try (ManagedBooking adjacentBooking =
            ManagedBooking.trackIfCreated(clients.adminBookings(), adjacentResponse)) {
          steps.run(
              "Verify adjacent booking accepted",
              () -> {
                assertThat(adjacentResponse.status()).isEqualTo(201);
                assertThat(adjacentBooking).isNotNull();
              });
        }
      }
    }
  }

  private static CreateBookingRequest booking(int roomId, LocalDate checkin, LocalDate checkout) {
    return new CreateBookingRequest(
        roomId,
        "Portfolio",
        "DateRuleGuest",
        false,
        new BookingDates(checkin, checkout),
        "portfolio.guest@example.com",
        "07123456789");
  }

  private static LocalDate futureDate(int additionalDays) {
    return LocalDate.now(ZoneOffset.UTC).plusDays(FUTURE_OFFSET_DAYS + additionalDays);
  }

  private static void assertRejectedAndCleanUpIfNecessary(
      ApiResult response, BookingClient cleanupClient) {
    try (ManagedBooking unexpected = ManagedBooking.trackIfCreated(cleanupClient, response)) {
      assertThat(response.status()).isEqualTo(409);
      assertThat(unexpected)
          .as("A rejected booking response must not contain a created booking id")
          .isNull();
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
