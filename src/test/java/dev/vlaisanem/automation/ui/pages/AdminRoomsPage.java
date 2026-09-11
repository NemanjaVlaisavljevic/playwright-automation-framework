package dev.vlaisanem.automation.ui.pages;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import dev.vlaisanem.automation.model.CreateRoomRequest;
import java.net.URI;
import java.util.regex.Pattern;

public final class AdminRoomsPage {
  private final Page page;
  private final Locator logout;
  private final Locator roomNameInput;
  private final Locator typeSelect;
  private final Locator accessibleSelect;
  private final Locator priceInput;
  private final Locator createButton;

  public AdminRoomsPage(Page page) {
    this.page = page;
    logout = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Logout"));
    // The create-row's room number and price inputs have no <label> (same accessibility gap as
    // ContactForm.java), so id-based locators are the only option.
    roomNameInput = page.locator("#roomName");
    typeSelect = page.locator("#type");
    accessibleSelect = page.locator("#accessible");
    priceInput = page.locator("#roomPrice");
    createButton = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create"));
  }

  public AdminRoomsPage assertLoaded() {
    assertThat(page).hasURL(Pattern.compile("/admin/rooms"));
    assertThat(logout).isVisible();
    return this;
  }

  /**
   * Fills and submits the inline create-room row, waiting for the {@code POST /api/room} response
   * so a caller that looks the room up afterward doesn't race the in-flight request.
   */
  public AdminRoomsPage createRoom(CreateRoomRequest room) {
    roomNameInput.fill(room.roomName());
    typeSelect.selectOption(room.type());
    accessibleSelect.selectOption(room.accessible() ? "true" : "false");
    priceInput.fill(String.valueOf(room.roomPrice()));
    for (String feature : room.features()) {
      page.getByRole(AriaRole.CHECKBOX, new Page.GetByRoleOptions().setName(feature)).check();
    }
    page.waitForResponse(
        candidate ->
            "POST".equals(candidate.request().method())
                && "/api/room".equals(URI.create(candidate.url()).getPath()),
        createButton::click);
    return this;
  }

  private Locator rowFor(String roomName) {
    // Rows are div-based (data-testid="roomlisting"), not <tr>.
    return page.locator(
        "[data-testid='roomlisting']", new Page.LocatorOptions().setHasText(roomName));
  }

  public AdminRoomsPage assertRoomListed(String roomName) {
    assertThat(rowFor(roomName)).isVisible();
    return this;
  }

  public AdminRoomsPage assertRoomNotListed(String roomName) {
    assertThat(rowFor(roomName)).hasCount(0);
    return this;
  }

  /** Deletes only the row matching {@code roomName} - never a page-global first match. */
  public AdminRoomsPage deleteRoom(String roomName) {
    rowFor(roomName).locator("span.roomDelete").click();
    return this;
  }

  /** Opens the room's detail page by clicking its row. */
  public AdminRoomDetailPage openRoom(String roomName) {
    rowFor(roomName).click();
    return new AdminRoomDetailPage(page);
  }
}
