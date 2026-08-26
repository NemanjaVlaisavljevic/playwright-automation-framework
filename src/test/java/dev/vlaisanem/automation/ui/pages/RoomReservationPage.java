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

  public RoomReservationPage assertBookingConfirmed() {
    assertThat(confirmedHeading).isVisible();
    return this;
  }
}
