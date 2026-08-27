package dev.vlaisanem.automation.tests.journey;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Page;
import dev.vlaisanem.automation.api.ApiContextFactory;
import dev.vlaisanem.automation.api.AuthClient;
import dev.vlaisanem.automation.api.RoomClient;
import dev.vlaisanem.automation.config.TestConfig;
import dev.vlaisanem.automation.core.AutomationTest;
import dev.vlaisanem.automation.data.ManagedRoom;
import dev.vlaisanem.automation.data.RoomTestData;
import dev.vlaisanem.automation.model.AuthCredentials;
import dev.vlaisanem.automation.model.CreateRoomRequest;
import dev.vlaisanem.automation.model.Room;
import dev.vlaisanem.automation.ui.pages.AdminLoginPage;
import dev.vlaisanem.automation.ui.pages.AdminRoomDetailPage;
import dev.vlaisanem.automation.ui.pages.AdminRoomsPage;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@AutomationTest
@Tag("journey")
@Tag("regression")
@Tag("room")
@Tag("mutation")
@ResourceLock("restful-booker-platform-mutations")
@Epic("Administration")
@Feature("Room management")
class AdminRoomManagementJourneyTest {

  @Test
  @DisplayName("Admin can create, edit, and delete a room through the admin UI")
  void adminCanManageRoomLifecycleThroughUi(
      Page page, APIRequestContext request, ApiContextFactory apiContexts, TestConfig config) {
    String token =
        new AuthClient(request)
            .loginAndReadToken(new AuthCredentials(config.adminUsername(), config.adminPassword()))
            .token();
    RoomClient rooms = new RoomClient(apiContexts.withCookie("token", token));
    CreateRoomRequest requested = RoomTestData.uniqueRoom();

    new AdminLoginPage(page).open().loginAs(config.adminUsername(), config.adminPassword());
    AdminRoomsPage roomsPage = new AdminRoomsPage(page).assertLoaded();

    // createVia() protects the create action AND the lookup together: if the room was actually
    // created server-side but something after that (the lookup, a network blip) throws, it still
    // finds and deletes the room by name before rethrowing - the try-with-resources below only
    // starts once a ManagedRoom is safely in hand, so it can't cover this earlier window itself.
    try (ManagedRoom managedRoom =
        ManagedRoom.createVia(rooms, requested, () -> roomsPage.createRoom(requested))) {
      Room createdRoom = rooms.getRoom(managedRoom.roomId()).bodyAs(Room.class);
      roomsPage.assertRoomListed(requested.roomName());
      assertThat(createdRoom.type()).isEqualTo(requested.type());
      assertThat(createdRoom.accessible()).isEqualTo(requested.accessible());
      assertThat(createdRoom.roomPrice()).isEqualTo(requested.roomPrice());

      AdminRoomDetailPage detailPage = roomsPage.openRoom(requested.roomName());
      detailPage.assertLoaded(requested.roomName());

      int updatedPrice = requested.roomPrice() + 50;
      detailPage.clickEdit().updatePrice(updatedPrice).submitUpdate();
      detailPage.assertPrice(updatedPrice);
      assertThat(rooms.getRoom(managedRoom.roomId()).bodyAs(Room.class).roomPrice())
          .isEqualTo(updatedPrice);

      page.navigate("/admin/rooms");
      AdminRoomsPage roomsPageAgain = new AdminRoomsPage(page).assertLoaded();
      roomsPageAgain.assertRoomListed(requested.roomName());
      roomsPageAgain.deleteRoom(requested.roomName());
      roomsPageAgain.assertRoomNotListed(requested.roomName());
      // Empirically confirmed against the running app (not assumed): GET on a deleted room
      // returns 500, not 404 - the room API does not treat "room not found" as a client error the
      // way the booking API does (ManagedBooking.close() verifies a real 404 there). Asserting the
      // actual observed behavior rather than the "should be" status, per this project's rule
      // against hardening unverified assumptions.
      assertThat(rooms.getRoom(managedRoom.roomId()).status()).isEqualTo(500);

      managedRoom.release();
    }
  }
}
