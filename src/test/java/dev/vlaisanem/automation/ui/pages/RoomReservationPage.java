package dev.vlaisanem.automation.ui.pages;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import dev.vlaisanem.automation.api.ApiResult;
import java.time.LocalDate;

public final class RoomReservationPage {

  private final Page page;
  private final Locator reserveNow;
  private final Locator firstName;
  private final Locator lastName;
  private final Locator email;
  private final Locator phone;
  private final Locator confirmedHeading;
  private final Locator validationAlert;

  /**
   * Navigates straight to a room's reservation page with an explicit date range, bypassing the
   * calendar widget (whose day cells do not update the dates actually submitted). Callers should
   * pass a room they created themselves and dates far from "today" so the booking cannot collide
   * with another guest's real, concurrent use of this shared public target.
   */
  public static RoomReservationPage openFor(
      Page page, int roomId, LocalDate checkin, LocalDate checkout) {
    page.navigate("/reservation/" + roomId + "?checkin=" + checkin + "&checkout=" + checkout);
    return new RoomReservationPage(page);
  }

  public RoomReservationPage(Page page) {
    this.page = page;
    reserveNow =
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Reserve Now"));
    firstName = page.getByLabel("Firstname");
    lastName = page.getByLabel("Lastname");
    email = page.getByLabel("Email", new Page.GetByLabelOptions().setExact(true));
    phone = page.getByLabel("Phone");
    confirmedHeading =
        page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Booking Confirmed"));
    // Rendered from the server's rejection response (bookingErrors.map(...) -> <li>), confirmed
    // against the running app's source (assets/src/components/reservation/BookingForm.tsx).
    // getByRole(ALERT) alone is ambiguous - it also matches Next.js's own hidden
    // "__next-route-announcer__" live region (role="alert", confirmed live) - so this is scoped to
    // the specific alert-danger class the form actually renders.
    validationAlert = page.locator("div.alert-danger[role='alert']");
  }

  public RoomReservationPage startReservation() {
    reserveNow.click();
    return this;
  }

  public RoomReservationPage fillGuestDetails(
      String guestFirstName, String guestLastName, String guestEmail, String guestPhone) {
    firstName.fill(guestFirstName);
    lastName.fill(guestLastName);
    email.fill(guestEmail);
    phone.fill(guestPhone);
    return this;
  }

  public ApiResult submitBooking() {
    Response response =
        page.waitForResponse(
            candidate ->
                candidate.url().contains("/api/booking")
                    && "POST".equals(candidate.request().method()),
            reserveNow::click);
    return new ApiResult(response.status(), response.headers(), response.text());
  }

  /**
   * Asserts the validation-error alert is showing exactly the given messages - confirms the
   * rejection actually reached the UI's error-rendering path, not just that a confirmation is
   * absent (which would also be true if the click itself never fired a request).
   */
  public RoomReservationPage assertValidationErrors(String... expectedErrors) {
    assertThat(validationAlert).isVisible();
    for (String expected : expectedErrors) {
      assertThat(validationAlert).containsText(expected);
    }
    return this;
  }

  public RoomReservationPage assertBookingConfirmed() {
    assertThat(confirmedHeading).isVisible();
    return this;
  }

  public RoomReservationPage assertBookingNotConfirmed() {
    assertThat(confirmedHeading).not().isVisible();
    return this;
  }
}
