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

  /** Adopts an already-created room (e.g. created via the UI) for safety-net cleanup. */
  public static ManagedRoom track(RoomClient rooms, int roomId, CreateRoomRequest request) {
    if (roomId <= 0) {
      throw new IllegalArgumentException("roomId must be positive");
    }
    return new ManagedRoom(rooms, roomId, request);
  }

  /**
   * Runs a caller-provided creation action (e.g. submitting the admin UI's create-room form) and
   * looks the resulting room up by name, with the same protective cleanup as {@link #create} - if
   * either the action or the lookup fails, this still attempts to find and delete the room by name
   * before rethrowing, so a room the action actually created server-side is never left behind just
   * because the lookup (or something else) failed before a {@code ManagedRoom} could be returned.
   * The original failure is preserved; any cleanup failure is attached as a suppressed exception.
   */
  public static ManagedRoom createVia(
      RoomClient rooms, CreateRoomRequest request, Runnable creationAction) {
    Integer createdRoomId = null;
    try {
      creationAction.run();
      createdRoomId = findRoomId(rooms, request.roomName());
      if (createdRoomId == null) {
        throw new AssertionError("Room created via the UI was not returned by the API");
      }
      return new ManagedRoom(rooms, createdRoomId, request);
    } catch (RuntimeException | AssertionError failure) {
      if (createdRoomId == null) {
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

  public int roomId() {
    return roomId;
  }

  public CreateRoomRequest request() {
    return request;
  }

  /**
   * Marks this room as already handled (e.g. the test itself deleted it via the UI as its main
   * action), so {@link #close()} does not also try to delete an already-deleted room.
   */
  public void release() {
    closed = true;
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
