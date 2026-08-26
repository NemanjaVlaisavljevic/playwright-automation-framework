package dev.vlaisanem.automation.tests.ui;

import com.microsoft.playwright.Page;
import dev.vlaisanem.automation.config.TestConfig;
import dev.vlaisanem.automation.core.AutomationTest;
import dev.vlaisanem.automation.ui.pages.AdminLoginPage;
import dev.vlaisanem.automation.ui.pages.AdminRoomsPage;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@AutomationTest
@Tag("ui")
@Tag("smoke")
@Tag("regression")
@Tag("auth")
@Tag("read-only")
@Epic("Administration")
@Feature("Admin login")
class AdminLoginTest {

  @Test
  @DisplayName("Admin with valid credentials reaches the room management screen")
  void validCredentialsReachAdminRooms(Page page, TestConfig config) {
    new AdminLoginPage(page).open().loginAs(config.adminUsername(), config.adminPassword());

    new AdminRoomsPage(page).assertLoaded();
  }

  @Test
  @DisplayName("Admin with an invalid password stays on the login screen")
  void invalidCredentialsShowError(Page page, TestConfig config) {
    new AdminLoginPage(page)
        .open()
        .loginAs(config.adminUsername(), "not-the-real-password")
        .assertInvalidCredentials();
  }
}
