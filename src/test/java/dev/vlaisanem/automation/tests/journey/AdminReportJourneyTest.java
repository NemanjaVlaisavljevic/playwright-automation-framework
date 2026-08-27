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
import dev.vlaisanem.automation.model.BookingDates;
import dev.vlaisanem.automation.model.CreateBookingRequest;
import dev.vlaisanem.automation.model.ReportEvent;
import dev.vlaisanem.automation.model.ReportResponse;
import dev.vlaisanem.automation.ui.pages.AdminLoginPage;
import dev.vlaisanem.automation.ui.pages.AdminReportPage;
import dev.vlaisanem.automation.ui.pages.AdminRoomsPage;
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
@Epic("Administration")
@Feature("Booking report")
class AdminReportJourneyTest {

  @Test
  @DisplayName("Report includes the booking with correct check-in and check-out dates")
  void reportIncludesBookingWithCorrectDates(
      Page page, APIRequestContext request, ApiContextFactory apiContexts, TestConfig config) {
    String token =
        new AuthClient(request)
            .loginAndReadToken(new AuthCredentials(config.adminUsername(), config.adminPassword()))
            .token();
    APIRequestContext adminRequest = apiContexts.withCookie("token", token);
    RoomClient rooms = new RoomClient(adminRequest);
    BookingClient bookings = new BookingClient(adminRequest);

    try (ManagedRoom room = ManagedRoom.create(rooms)) {
      // The report calendar defaults to the current month - pick an offset guaranteed to stay
      // within it, favoring a look-back near month-end (the API already accepts past-dated
      // bookings - see BookingDateRulesApiTest's documented known gap for that) over a look-ahead
      // that could roll into next month.
      LocalDate today = LocalDate.now();
      int daysLeftInMonth = today.lengthOfMonth() - today.getDayOfMonth();
      LocalDate checkin = daysLeftInMonth >= 2 ? today.plusDays(2) : today.minusDays(2);
      LocalDate checkout = checkin.plusDays(1);
      CreateBookingRequest requested =
          new CreateBookingRequest(
              room.roomId(),
              "Portfolio",
              "Guest",
              false,
              new BookingDates(checkin, checkout),
              "portfolio.guest@example.com",
              "07123456789");

      try (ManagedBooking booking = ManagedBooking.create(bookings, bookings, requested)) {
        new AdminLoginPage(page).open().loginAs(config.adminUsername(), config.adminPassword());
        new AdminRoomsPage(page).assertLoaded();

        String expectedEventText =
            requested.firstName()
                + " "
                + requested.lastName()
                + " - Room: "
                + room.request().roomName();

        AdminReportPage reportPage = new AdminReportPage(page);
        ApiResult reportResponse = reportPage.openAndCaptureReport();
        ReportEvent matchingEvent =
            reportResponse.bodyAs(ReportResponse.class).report().stream()
                .filter(event -> event.title().equals(expectedEventText))
                .findFirst()
                .orElseThrow(
                    () -> new AssertionError("Report did not include the expected booking event"));
        assertThat(matchingEvent.start()).isEqualTo(checkin);
        assertThat(matchingEvent.end()).isEqualTo(checkout);

        // The report data itself proves the dates are correct (above) - this only additionally
        // confirms the calendar actually renders that event, which it can only do within its
        // currently-displayed month (see the month-boundary-safe offset chosen above).
        reportPage.assertEventVisible(expectedEventText);
      }
    }
  }
}
