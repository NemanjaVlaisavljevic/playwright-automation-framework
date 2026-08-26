package dev.vlaisanem.automation.tests.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIRequestContext;
import dev.vlaisanem.automation.api.ApiResult;
import dev.vlaisanem.automation.api.RoomClient;
import dev.vlaisanem.automation.core.AutomationTest;
import dev.vlaisanem.automation.model.RoomsResponse;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@AutomationTest
@Tag("api")
@Tag("smoke")
@Tag("regression")
@Tag("room")
@Tag("read-only")
@Epic("Room inventory")
@Feature("Room API contract")
class RoomApiContractTest {

  @Test
  @DisplayName("GET /api/room returns a usable room inventory")
  @Severity(SeverityLevel.BLOCKER)
  void roomInventoryMatchesContract(APIRequestContext request) {
    ApiResult response = new RoomClient(request).listRooms();

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.header("content-type")).containsIgnoringCase("application/json");
    assertThat(response.schemaErrors("schemas/rooms-response.schema.json")).isEmpty();

    RoomsResponse inventory = response.bodyAs(RoomsResponse.class);
    assertThat(inventory.rooms())
        .isNotEmpty()
        .allSatisfy(
            room -> {
              assertThat(room.roomId()).isPositive();
              assertThat(room.roomName()).isNotBlank();
              assertThat(room.type()).isIn("Single", "Twin", "Double", "Family", "Suite");
              assertThat(room.roomPrice()).isPositive();
            });
  }
}
