package dev.vlaisanem.automation.tests.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIRequestContext;
import dev.vlaisanem.automation.api.ApiContextFactory;
import dev.vlaisanem.automation.api.ApiResult;
import dev.vlaisanem.automation.api.AuthClient;
import dev.vlaisanem.automation.api.RoomClient;
import dev.vlaisanem.automation.config.TestConfig;
import dev.vlaisanem.automation.core.AutomationTest;
import dev.vlaisanem.automation.data.ManagedRoom;
import dev.vlaisanem.automation.model.AuthCredentials;
import dev.vlaisanem.automation.model.CreateRoomRequest;
import dev.vlaisanem.automation.model.Room;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@AutomationTest
@Tag("api")
@Tag("regression")
@Tag("room")
@Tag("mutation")
@ResourceLock("restful-booker-platform-mutations")
@Epic("Room inventory")
@Feature("Room lifecycle")
class RoomCrudApiTest {

  @Test
  @DisplayName("Admin can create, read, update, and delete an isolated room")
  void adminCanManageRoomLifecycle(
      APIRequestContext request, ApiContextFactory apiContexts, TestConfig config) {
    AuthClient auth = new AuthClient(request);
    String token =
        auth.loginAndReadToken(new AuthCredentials(config.adminUsername(), config.adminPassword()))
            .token();
    RoomClient rooms = new RoomClient(apiContexts.withCookie("token", token));

    try (ManagedRoom managedRoom = ManagedRoom.create(rooms)) {
      CreateRoomRequest roomRequest = managedRoom.request();
      int roomId = managedRoom.roomId();

      ApiResult read = rooms.getRoom(roomId);
      assertThat(read.status()).isEqualTo(200);
      assertThat(read.bodyAs(Room.class).roomName()).isEqualTo(roomRequest.roomName());

      CreateRoomRequest updated =
          new CreateRoomRequest(
              roomRequest.roomName(),
              roomRequest.type(),
              roomRequest.accessible(),
              roomRequest.image(),
              roomRequest.description(),
              175,
              roomRequest.features());
      assertThat(rooms.updateRoom(roomId, updated).status()).isEqualTo(202);
      assertThat(rooms.getRoom(roomId).bodyAs(Room.class).roomPrice()).isEqualTo(175);
    }
  }
}
