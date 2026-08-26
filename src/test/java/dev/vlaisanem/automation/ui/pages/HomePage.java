package dev.vlaisanem.automation.ui.pages;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import dev.vlaisanem.automation.api.ApiResult;
import dev.vlaisanem.automation.model.Room;
import dev.vlaisanem.automation.ui.components.ContactForm;
import java.net.URI;
import java.util.List;

public final class HomePage {

  private final Page page;
  private final Locator heroHeading;
  private final Locator roomsHeading;
  private final Locator bookNowButtons;

  public HomePage(Page page) {
    this.page = page;
    heroHeading =
        page.getByRole(
            AriaRole.HEADING,
            new Page.GetByRoleOptions().setName("Welcome to Shady Meadows B&B").setExact(true));
    roomsHeading =
        page.getByRole(
            AriaRole.HEADING, new Page.GetByRoleOptions().setName("Our Rooms").setExact(true));
    bookNowButtons =
        page.getByRole(
            AriaRole.LINK, new Page.GetByRoleOptions().setName("Book now").setExact(true));
  }

  public HomePage open() {
    page.navigate("/");
    return this;
  }

  /** Opens the page and returns the exact room-inventory response consumed by the frontend. */
  public ApiResult openAndCaptureRoomInventory() {
    Response response =
        page.waitForResponse(
            candidate ->
                "GET".equals(candidate.request().method())
                    && "/api/room".equals(URI.create(candidate.url()).getPath()),
            () -> page.navigate("/"));
    return new ApiResult(response.status(), response.headers(), response.text());
  }

  public HomePage assertLoaded() {
    assertThat(page).hasTitle(java.util.regex.Pattern.compile("Restful-booker-platform demo"));
    assertThat(heroHeading).isVisible();
    assertThat(roomsHeading).isVisible();
    assertThat(bookNowButtons.first()).isVisible();
    return this;
  }

  public int bookableRoomCount() {
    return bookNowButtons.count();
  }

  /**
   * Asserts the rendered "Book now" links match {@code expectedRooms} exactly, in order - both in
   * count and in which room each link points at (the homepage only ever features a subset of the
   * full API inventory, so callers must pass the subset they actually expect to see).
   */
  public void assertBookableRooms(List<Room> expectedRooms) {
    assertThat(bookNowButtons).hasCount(expectedRooms.size());
    List<String> hrefs =
        bookNowButtons.all().stream().map(link -> link.getAttribute("href")).toList();
    for (int i = 0; i < expectedRooms.size(); i++) {
      String expectedPath = "/reservation/" + expectedRooms.get(i).roomId();
      String actualPath = URI.create(hrefs.get(i)).getPath();
      org.assertj.core.api.Assertions.assertThat(actualPath)
          .as("Book now link #%d href path", i)
          .isEqualTo(expectedPath);
    }
  }

  public ContactForm contactForm() {
    return new ContactForm(page);
  }
}
