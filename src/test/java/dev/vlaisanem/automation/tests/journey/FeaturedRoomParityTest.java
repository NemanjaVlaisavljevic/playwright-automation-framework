package dev.vlaisanem.automation.tests.journey;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import dev.vlaisanem.automation.api.ApiResult;
import dev.vlaisanem.automation.core.AutomationTest;
import dev.vlaisanem.automation.model.Room;
import dev.vlaisanem.automation.model.RoomsResponse;
import dev.vlaisanem.automation.ui.pages.HomePage;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@AutomationTest
@Tag("journey")
@Tag("regression")
@Tag("room")
@Tag("read-only")
@Epic("Room inventory")
@Feature("API and UI consistency")
class FeaturedRoomParityTest {

  private static final int FEATURED_ROOM_COUNT = 3;

  @Test
  @DisplayName("Homepage renders the first three API rooms as booking actions")
  void homepageRendersFirstThreeApiRoomsAsBookingActions(Page page) {
    HomePage homePage = new HomePage(page);
    ApiResult inventoryResponse = homePage.openAndCaptureRoomInventory();

    assertThat(inventoryResponse.status()).isEqualTo(200);
    RoomsResponse apiInventory = inventoryResponse.bodyAs(RoomsResponse.class);
    List<Room> expectedRooms = apiInventory.rooms().stream().limit(FEATURED_ROOM_COUNT).toList();
    homePage.assertLoaded().assertBookableRooms(expectedRooms);
  }
}
