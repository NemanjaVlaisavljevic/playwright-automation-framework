package dev.vlaisanem.automation.api;

import com.microsoft.playwright.APIRequestContext;
import dev.vlaisanem.automation.model.CreateBookingRequest;
import java.time.LocalDate;

public final class BookingClient extends BaseApiClient {
  private static final String BOOKINGS_PATH = "/api/booking";

  public BookingClient(APIRequestContext request) {
    super(request);
  }

  public ApiResult listBookingsForRoom(int roomId) {
    return get(BOOKINGS_PATH + "?roomid=" + roomId);
  }

  public ApiResult getBooking(int bookingId) {
    return get(BOOKINGS_PATH + "/" + bookingId);
  }

  public ApiResult createBooking(CreateBookingRequest booking) {
    return post(BOOKINGS_PATH, booking);
  }

  public ApiResult updateBooking(int bookingId, CreateBookingRequest booking) {
    return put(BOOKINGS_PATH + "/" + bookingId, booking);
  }

  public ApiResult deleteBooking(int bookingId) {
    return delete(BOOKINGS_PATH + "/" + bookingId);
  }

  /** The room's API requires {@code roomid}; without it this endpoint returns 400. */
  public ApiResult unavailableBookings(int roomId, LocalDate checkin, LocalDate checkout) {
    return get(
        BOOKINGS_PATH
            + "/unavailable?roomid="
            + roomId
            + "&checkin="
            + checkin
            + "&checkout="
            + checkout);
  }
}
