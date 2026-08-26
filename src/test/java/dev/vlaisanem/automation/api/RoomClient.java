package dev.vlaisanem.automation.api;

import com.microsoft.playwright.APIRequestContext;
import dev.vlaisanem.automation.model.CreateRoomRequest;
import dev.vlaisanem.automation.model.RoomsResponse;

public final class RoomClient extends BaseApiClient {
  private static final String ROOMS_PATH = "/api/room/";

  public RoomClient(APIRequestContext request) {
    super(request);
  }

  public ApiResult listRooms() {
    return get(ROOMS_PATH);
  }

  public RoomsResponse rooms() {
    return listRooms().bodyAs(RoomsResponse.class);
  }

  public ApiResult getRoom(int roomId) {
    return get(ROOMS_PATH + roomId);
  }

  public ApiResult createRoom(CreateRoomRequest room) {
    return post(ROOMS_PATH, room);
  }

  public ApiResult updateRoom(int roomId, CreateRoomRequest room) {
    return put(ROOMS_PATH + roomId, room);
  }

  public ApiResult deleteRoom(int roomId) {
    return delete(ROOMS_PATH + roomId);
  }
}
