package dev.vlaisanem.automation.data;

import dev.vlaisanem.automation.api.ApiResult;
import dev.vlaisanem.automation.api.RoomClient;
import dev.vlaisanem.automation.model.CreateRoomRequest;
import dev.vlaisanem.automation.model.Room;

/** A test-owned room that is deleted automatically when its scope ends. */
public final class ManagedRoom implements AutoCloseable {
  private final RoomClient rooms;
  private final int roomId;
  private final CreateRoomRequest request;
  private boolean closed;

  private ManagedRoom(RoomClient rooms, int roomId, CreateRoomRequest request) {
    this.rooms = rooms;
    this.roomId = roomId;
    this.request = request;
  }

  public static ManagedRoom create(RoomClient rooms) {
    CreateRoomRequest request = RoomTestData.uniqueRoom();
    ApiResult creation = rooms.createRoom(request);
    Integer createdRoomId = null;

    try {
      requireStatus(creation, 200, "create test room");
      createdRoomId = findRoomId(rooms, request.roomName());
      if (createdRoomId == null) {
        throw new AssertionError("Created room was not returned by the API");
      }
      return new ManagedRoom(rooms, createdRoomId, request);
    } catch (RuntimeException | AssertionError failure) {
      if (createdRoomId == null && creation.isSuccessful()) {
        try {
          createdRoomId = findRoomId(rooms, request.roomName());
        } catch (RuntimeException lookupFailure) {
          failure.addSuppressed(lookupFailure);
        }
      }
      if (createdRoomId != null) {
        cleanupAfterSetupFailure(rooms, createdRoomId, failure);
      }
      throw failure;
    }
  }

  private static Integer findRoomId(RoomClient rooms, String roomName) {
    return rooms.rooms().rooms().stream()
        .filter(room -> room.roomName().equals(roomName))
        .map(Room::roomId)
        .findFirst()
        .orElse(null);
  }

  public int roomId() {
    return roomId;
  }

  public CreateRoomRequest request() {
    return request;
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    requireStatus(rooms.deleteRoom(roomId), 202, "delete test room " + roomId);
  }

  private static void cleanupAfterSetupFailure(
      RoomClient rooms, int roomId, Throwable originalFailure) {
    try {
      requireStatus(rooms.deleteRoom(roomId), 202, "clean up test room " + roomId);
    } catch (RuntimeException | AssertionError cleanupFailure) {
      originalFailure.addSuppressed(cleanupFailure);
    }
  }

  private static void requireStatus(ApiResult response, int expected, String operation) {
    if (response.status() != expected) {
      throw new AssertionError(
          operation + " returned " + response.status() + " instead of " + expected);
    }
  }
}
