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
import dev.vlaisanem.automation.data.BookingTestData;
import dev.vlaisanem.automation.data.ManagedBooking;
import dev.vlaisanem.automation.data.ManagedRoom;
import dev.vlaisanem.automation.model.AuthCredentials;
import dev.vlaisanem.automation.model.BookingResponse;
import dev.vlaisanem.automation.model.CreateBookingRequest;
import dev.vlaisanem.automation.ui.pages.AdminLoginPage;
import dev.vlaisanem.automation.ui.pages.AdminRoomDetailPage;
import dev.vlaisanem.automation.ui.pages.AdminRoomsPage;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
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
@Epic("Administration")
@Feature("Booking management")
class AdminBookingManagementJourneyTest {

  @Test
  @DisplayName("Admin can view, edit, and delete a room's booking through the admin UI")
  void adminCanManageBookingLifecycleThroughUi(
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
      CreateBookingRequest requested = BookingTestData.uniqueBooking(room.roomId());
      try (ManagedBooking booking =
          steps.call(
              "Provision an existing booking",
              () -> ManagedBooking.create(bookings, bookings, requested))) {
        BookingResponse created = booking.created();

        steps.run(
            "Log in to the admin UI",
            () -> {
              new AdminLoginPage(page)
                  .open()
                  .loginAs(config.adminUsername(), config.adminPassword());
              new AdminRoomsPage(page).assertLoaded();
            });

        AdminRoomDetailPage detailPage =
            steps.call(
                "Open room detail page",
                () -> {
                  page.navigate("/admin/room/" + room.roomId());
                  return new AdminRoomDetailPage(page).assertLoaded(room.request().roomName());
                });

        steps.run(
            "Verify booking listed",
            () ->
                detailPage.assertBookingListed(
                    created.firstName(),
                    created.lastName(),
                    created.bookingDates().checkin(),
                    created.bookingDates().checkout()));

        String updatedLastName = "Updated-" + created.lastName();
        steps.run(
            "Edit the booking's last name",
            () ->
                detailPage
                    .editBooking(created.firstName(), created.lastName())
                    .fillBookingEdit(created.firstName(), updatedLastName)
                    .confirmBookingEdit());

        steps.run(
            "Verify updated booking",
            () -> {
              detailPage.assertBookingListed(
                  created.firstName(),
                  updatedLastName,
                  created.bookingDates().checkin(),
                  created.bookingDates().checkout());
              ApiResult persisted = bookings.getBooking(booking.bookingId());
              assertThat(persisted.status()).isEqualTo(200);
              assertThat(persisted.bodyAs(BookingResponse.class).lastName())
                  .isEqualTo(updatedLastName);
            });

        steps.run(
            "Delete the booking through the admin UI",
            () -> detailPage.deleteBooking(created.firstName(), updatedLastName));

        steps.run(
            "Verify booking deletion",
            () -> {
              detailPage.assertBookingNotListed(created.firstName(), updatedLastName);
              assertThat(bookings.getBooking(booking.bookingId()).status()).isEqualTo(404);
            });

        booking.release();
      }
    }
  }
}
