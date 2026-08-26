package dev.vlaisanem.automation.data;

import dev.vlaisanem.automation.model.CreateRoomRequest;
import java.util.List;
import java.util.UUID;

public final class RoomTestData {
  private RoomTestData() {}

  public static CreateRoomRequest uniqueRoom() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    return new CreateRoomRequest(
        "portfolio-" + suffix,
        "Single",
        true,
        "https://automationintesting.online/images/room2.jpg",
        "Ephemeral room created by the opt-in portfolio automation suite.",
        125,
        List.of("WiFi", "Safe"));
  }
}
