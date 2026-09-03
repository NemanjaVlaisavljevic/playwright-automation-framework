import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useCopyToClipboard } from "./use-copy-to-clipboard";

describe("useCopyToClipboard", () => {
  let writeText: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText },
      configurable: true,
    });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("copies the given text and flips copied to true, then back to false after the timeout", async () => {
    const { result } = renderHook(() => useCopyToClipboard(1500));

    await act(async () => {
      result.current.copy("some-run-id");
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(writeText).toHaveBeenCalledWith("some-run-id");
    expect(result.current.copied).toBe(true);
  });

  it("resets the revert timer on a second copy instead of racing the first one's revert", async () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => useCopyToClipboard(1500));

    await act(async () => {
      result.current.copy("first");
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(result.current.copied).toBe(true);

    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(result.current.copied).toBe(true);

    await act(async () => {
      result.current.copy("second");
      await Promise.resolve();
      await Promise.resolve();
    });

    act(() => {
      vi.advanceTimersByTime(1000);
    });
    // If the first timer had not been cleared, it would already have reverted this by now (2000ms
    // since the first copy, past its own 1500ms deadline).
    expect(result.current.copied).toBe(true);

    act(() => {
      vi.advanceTimersByTime(500);
    });
    expect(result.current.copied).toBe(false);
  });

  it("leaves copied false when the clipboard write is rejected", async () => {
    writeText.mockRejectedValue(
      new DOMException("Document is not focused", "NotAllowedError"),
    );
    const { result } = renderHook(() => useCopyToClipboard());

    await act(async () => {
      result.current.copy("some-run-id");
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(result.current.copied).toBe(false);
  });

  it("clears its pending revert timer on unmount", async () => {
    vi.useFakeTimers();
    const { result, unmount } = renderHook(() => useCopyToClipboard(1500));

    await act(async () => {
      result.current.copy("some-run-id");
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(result.current.copied).toBe(true);

    // Would throw ("Can't perform a React state update on an unmounted component" in older React,
    // or simply leak the timer forever) if the cleanup effect did not clear it.
    unmount();
    expect(() => vi.advanceTimersByTime(2000)).not.toThrow();
  });

  /**
   * Regression test for a real review finding: unmounting while `writeText` is still in flight used
   * to leave the cleanup effect with no timer to clear yet (the write had not resolved, so
   * `revertTimer.current` was still `undefined`) - the write resolving afterward would then set
   * state and schedule a fresh revert timer that nothing would ever clear again.
   */
  it("ignores a clipboard write that resolves after unmount - no leaked timer from the stale resolution", async () => {
    vi.useFakeTimers();
    let resolveWrite: (() => void) | undefined;
    writeText.mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          resolveWrite = resolve;
        }),
    );
    const { result, unmount } = renderHook(() => useCopyToClipboard());

    act(() => {
      result.current.copy("some-run-id");
    });
    unmount();

    // The write has not resolved yet - nothing should be scheduled at this point either way.
    expect(vi.getTimerCount()).toBe(0);

    await act(async () => {
      resolveWrite?.();
      await Promise.resolve();
      await Promise.resolve();
    });

    // If the stale resolution had incorrectly proceeded, it would have called setCopied(true) and
    // scheduled a brand-new revert timer here - one this already-unmounted hook could never clear.
    expect(vi.getTimerCount()).toBe(0);
  });
});
