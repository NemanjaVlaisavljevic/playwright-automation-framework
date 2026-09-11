import { useEffect, useRef, useState } from "react";

export interface UseCopyToClipboardResult {
  /** `true` for `revertAfterMs` after a successful copy, then automatically flips back. */
  copied: boolean;
  copy: (text: string) => void;
}

/**
 * Shared "click to copy, briefly confirm, then revert" behavior. A second `copy()` call before the
 * previous timer fires resets the revert deadline rather than racing it.
 *
 * `requestId` is bumped on both a new `copy()` and unmount, invalidating any in-flight `writeText`
 * call so it becomes a no-op instead of calling `setState` on an unmounted component or leaking an
 * uncleared timer.
 *
 * A rejected `writeText` (permission denied, unsupported browser) is swallowed silently - `copied`
 * simply never flips to `true`, and the caller can still select the text by hand.
 */
export function useCopyToClipboard(
  revertAfterMs = 1500,
): UseCopyToClipboardResult {
  const [copied, setCopied] = useState(false);
  const revertTimer = useRef<ReturnType<typeof setTimeout> | undefined>(
    undefined,
  );
  const requestId = useRef(0);

  useEffect(() => {
    return () => {
      requestId.current += 1;
      if (revertTimer.current !== undefined) {
        clearTimeout(revertTimer.current);
      }
    };
  }, []);

  function copy(text: string) {
    const thisRequestId = ++requestId.current;
    void (async () => {
      try {
        await navigator.clipboard.writeText(text);
        if (requestId.current !== thisRequestId) {
          return;
        }
        setCopied(true);
        if (revertTimer.current !== undefined) {
          clearTimeout(revertTimer.current);
        }
        revertTimer.current = setTimeout(() => setCopied(false), revertAfterMs);
      } catch {
        // Deliberately not surfaced - see doc comment above.
      }
    })();
  }

  return { copied, copy };
}
