package dev.vlaisanem.automation.data;

import dev.vlaisanem.automation.api.ApiResult;
import dev.vlaisanem.automation.api.BookingClient;
import dev.vlaisanem.automation.model.BookingResponse;
import dev.vlaisanem.automation.model.CreateBookingRequest;
import dev.vlaisanem.automation.support.JsonSupport;

/** A test-owned booking that is deleted automatically when its scope ends. */
public final class ManagedBooking implements AutoCloseable {
  private final BookingClient cleanupClient;
  private final int bookingId;
  private final BookingResponse created;
  private boolean closed;

  private ManagedBooking(BookingClient cleanupClient, int bookingId, BookingResponse created) {
    this.cleanupClient = cleanupClient;
    this.bookingId = bookingId;
    this.created = created;
  }

  public static ManagedBooking create(
      BookingClient creator, BookingClient cleanupClient, CreateBookingRequest bookingRequest) {
    ApiResult creation = creator.createBooking(bookingRequest);
    ManagedBooking tracked = trackIfCreated(cleanupClient, creation);

    try {
      requireStatus(creation, 201, "create test booking");
      if (tracked == null) {
        throw new AssertionError(
            "Successful booking response did not contain a positive bookingid");
      }
      BookingResponse created = creation.bodyAs(BookingResponse.class);
      return new ManagedBooking(cleanupClient, tracked.bookingId, created);
    } catch (RuntimeException | AssertionError failure) {
      if (tracked != null) {
        tracked.closeAfterFailure(failure);
      }
      throw failure;
    }
  }

  public static ManagedBooking track(BookingClient cleanupClient, int bookingId) {
    if (bookingId <= 0) {
      throw new IllegalArgumentException("bookingId must be positive");
    }
    return new ManagedBooking(cleanupClient, bookingId, null);
  }

  /** Returns a managed resource only when a response contains a created booking id. */
  public static ManagedBooking trackIfCreated(BookingClient cleanupClient, ApiResult response) {
    try {
      var bookingId = JsonSupport.tree(response.body()).get("bookingid");
      return bookingId != null && bookingId.canConvertToInt() && bookingId.asInt() > 0
          ? track(cleanupClient, bookingId.asInt())
          : null;
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  public int bookingId() {
    return bookingId;
  }

  /**
   * Marks this booking as already handled (e.g. the test itself deleted it via the UI as its main
   * action), so {@link #close()} does not also try to delete an already-deleted booking.
   */
  public void release() {
    closed = true;
  }

  public BookingResponse created() {
    if (created == null) {
      throw new IllegalStateException("This managed booking tracks an existing response only");
    }
    return created;
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    requireStatus(cleanupClient.deleteBooking(bookingId), 202, "delete test booking " + bookingId);
    requireStatus(
        cleanupClient.getBooking(bookingId), 404, "verify deletion of test booking " + bookingId);
  }

  private void closeAfterFailure(Throwable originalFailure) {
    try {
      close();
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
