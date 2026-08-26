package dev.vlaisanem.automation.tests.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import dev.vlaisanem.automation.core.AutomationTest;
import dev.vlaisanem.automation.ui.pages.HomePage;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@AutomationTest
@Tag("ui")
@Tag("smoke")
@Tag("regression")
@Tag("room")
@Tag("read-only")
@Epic("Guest experience")
@Feature("Room discovery")
class HomePageTest {

  @Test
  @DisplayName("Guest can see at least one bookable room")
  void guestCanDiscoverBookableRooms(Page page) {
    HomePage homePage = new HomePage(page).open().assertLoaded();

    assertThat(homePage.bookableRoomCount()).isPositive();
  }
}
