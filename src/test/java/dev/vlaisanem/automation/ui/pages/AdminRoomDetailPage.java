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
    // View-mode markup is confirmed (live DOM) to be <p>Room price: <span>100</span></p> - the
    // value lives in its own <span>, so this can be asserted precisely instead of substring-matched
    // against the whole "Room price: N" line.
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
   * Asserts the view-mode price value equals {@code expectedPrice} exactly. Uses Playwright's
   * auto-retrying {@code hasText} (anchored to a full match) rather than a one-shot {@code
   * textContent()} snapshot: after Update, the form closes and the page re-fetches the room
   * asynchronously, so a snapshot read risks reading a stale value mid-reload - and an unanchored
   * substring match (e.g. "175") would also wrongly match a stale/different value like "1750".
   */
  public AdminRoomDetailPage assertPrice(int expectedPrice) {
    assertThat(priceValue).hasText(Pattern.compile("^" + expectedPrice + "$"));
    return this;
  }

  /**
   * Booking rows are {@code <div class="detail booking-{n}">} (confirmed against the live DOM), but
   * {@code {n}} is NOT confirmed to be the API's bookingId (a first attempt at assuming that failed
   * against real data) - locate by guest name instead, which this project's tests always generate
   * uniquely per booking (see BookingTestData).
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
   * Fills the booking row's inline edit inputs. Confirmed live: while a row is being edited, it is
   * the ONLY row rendering real form controls (other rows stay plain text), so unscoped {@code
   * input} locators are safe here - in DOM order: firstname, lastname, deposit paid (a separate
   * {@code <select>}, not an input), checkin (dd/mm/yyyy), checkout (dd/mm/yyyy). None of these
   * fields have accessible labels - another instance of the same gap already documented in
   * ContactForm.java.
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
