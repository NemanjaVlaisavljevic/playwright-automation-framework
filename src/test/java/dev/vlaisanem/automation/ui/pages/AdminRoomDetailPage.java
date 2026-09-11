package dev.vlaisanem.automation.ui.pages;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import java.time.LocalDate;
import java.util.regex.Pattern;

/** The admin room detail/edit page at {@code /admin/room/{id}}, including its booking table. */
public final class AdminRoomDetailPage {
  private final Page page;
  private final Locator editButton;
  private final Locator updateButton;
  private final Locator cancelButton;
  private final Locator roomNameInput;
  private final Locator priceInput;
  private final Locator priceValue;

  public AdminRoomDetailPage(Page page) {
    this.page = page;
    editButton = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Edit"));
    updateButton = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Update"));
    cancelButton = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Cancel"));
    // Same id-based locators as AdminRoomsPage's create row - the edit form reuses these ids and
    // has the same missing-label gap.
    roomNameInput = page.locator("#roomName");
    priceInput = page.locator("#roomPrice");
    // View-mode markup is <p>Room price: <span>100</span></p>; the value has its own <span>, so
    // it can be matched exactly rather than by substring.
    priceValue = page.locator("p:has-text('Room price:') span");
  }

  public AdminRoomDetailPage assertLoaded(String roomName) {
    assertThat(
            page.getByRole(
                AriaRole.HEADING, new Page.GetByRoleOptions().setName("Room: " + roomName)))
        .isVisible();
    return this;
  }

  public AdminRoomDetailPage clickEdit() {
    editButton.click();
    assertThat(roomNameInput).isVisible();
    return this;
  }

  public AdminRoomDetailPage updatePrice(int newPrice) {
    priceInput.fill(String.valueOf(newPrice));
    return this;
  }

  public AdminRoomDetailPage submitUpdate() {
    updateButton.click();
    assertThat(updateButton).not().isVisible();
    return this;
  }

  public AdminRoomDetailPage cancelEdit() {
    cancelButton.click();
    return this;
  }

  /**
   * Asserts the view-mode price equals {@code expectedPrice} exactly, using an anchored,
   * auto-retrying match: after Update the page re-fetches asynchronously, so a one-shot read risks
   * a stale value, and an unanchored match could wrongly match e.g. "1750" for "175".
   */
  public AdminRoomDetailPage assertPrice(int expectedPrice) {
    assertThat(priceValue).hasText(Pattern.compile("^" + expectedPrice + "$"));
    return this;
  }

  /**
   * Booking rows are {@code <div class="detail booking-{n}">}, but {@code {n}} is not the API's
   * bookingId - locate by guest name instead, which tests always generate uniquely (see
   * BookingTestData).
   */
  private Locator bookingRow(String firstName, String lastName) {
    return page.locator(".detail")
        .filter(new Locator.FilterOptions().setHasText(firstName))
        .filter(new Locator.FilterOptions().setHasText(lastName));
  }

  public AdminRoomDetailPage assertBookingListed(
      String firstName, String lastName, LocalDate checkin, LocalDate checkout) {
    Locator row = bookingRow(firstName, lastName);
    assertThat(row).isVisible();
    assertThat(row).containsText(checkin.toString());
    assertThat(row).containsText(checkout.toString());
    return this;
  }

  public AdminRoomDetailPage assertBookingNotListed(String firstName, String lastName) {
    assertThat(bookingRow(firstName, lastName)).hasCount(0);
    return this;
  }

  /** Opens the given booking row's inline edit form (pencil icon). */
  public AdminRoomDetailPage editBooking(String firstName, String lastName) {
    bookingRow(firstName, lastName).locator("span.bookingEdit").click();
    return this;
  }

  /**
   * Fills the booking row's inline edit inputs. Only the row being edited renders real form
   * controls, so unscoped {@code input} locators are safe here; DOM order is firstname, lastname.
   * No accessible labels (same gap as ContactForm.java).
   */
  public AdminRoomDetailPage fillBookingEdit(String newFirstName, String newLastName) {
    Locator inputs = page.locator("input");
    inputs.nth(0).fill(newFirstName);
    inputs.nth(1).fill(newLastName);
    return this;
  }

  public AdminRoomDetailPage confirmBookingEdit() {
    page.locator("span.confirmBookingEdit").click();
    return this;
  }

  public AdminRoomDetailPage cancelBookingEdit() {
    page.locator("span.exitBookingEdit").click();
    return this;
  }

  /** Deletes only the row matching this guest name - never a page-global first match. */
  public AdminRoomDetailPage deleteBooking(String firstName, String lastName) {
    bookingRow(firstName, lastName).locator("span.bookingDelete").click();
    return this;
  }
}
