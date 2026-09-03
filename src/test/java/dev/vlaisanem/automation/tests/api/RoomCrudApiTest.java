package dev.vlaisanem.automation.tests.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIRequestContext;
import dev.vlaisanem.automation.api.ApiContextFactory;
import dev.vlaisanem.automation.api.ApiResult;
import dev.vlaisanem.automation.api.AuthClient;
import dev.vlaisanem.automation.api.RoomClient;
import dev.vlaisanem.automation.config.TestConfig;
import dev.vlaisanem.automation.core.AutomationTest;
import dev.vlaisanem.automation.core.Steps;
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
      APIRequestContext request, ApiContextFactory apiContexts, TestConfig config, Steps steps) {
    AuthClient auth = new AuthClient(request);
    String token =
        steps.call(
            "Authenticate as admin",
            () ->
                auth.loginAndReadToken(
                        new AuthCredentials(config.adminUsername(), config.adminPassword()))
                    .token());
    RoomClient rooms = new RoomClient(apiContexts.withCookie("token", token));

    try (ManagedRoom managedRoom =
        steps.call("Provision an available room", () -> ManagedRoom.create(rooms))) {
      CreateRoomRequest roomRequest = managedRoom.request();
      int roomId = managedRoom.roomId();

      ApiResult read = steps.call("Read the room", () -> rooms.getRoom(roomId));
      steps.run(
          "Verify read room",
          () -> {
            assertThat(read.status()).isEqualTo(200);
            assertThat(read.bodyAs(Room.class).roomName()).isEqualTo(roomRequest.roomName());
          });

      CreateRoomRequest updated =
          new CreateRoomRequest(
              roomRequest.roomName(),
              roomRequest.type(),
              roomRequest.accessible(),
              roomRequest.image(),
              roomRequest.description(),
              175,
              roomRequest.features());
      ApiResult update = steps.call("Update the room", () -> rooms.updateRoom(roomId, updated));
      steps.run("Verify update accepted", () -> assertThat(update.status()).isEqualTo(202));

      steps.run(
          "Verify updated room persists on re-read",
          () -> assertThat(rooms.getRoom(roomId).bodyAs(Room.class).roomPrice()).isEqualTo(175));
    }
  }
}
