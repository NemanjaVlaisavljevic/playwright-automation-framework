import { useEffect, useRef, useState } from "react";

export interface UseCopyToClipboardResult {
  /** `true` for `revertAfterMs` after a successful copy, then automatically flips back. */
  copied: boolean;
  copy: (text: string) => void;
}

/**
 * Shared "click to copy, briefly confirm, then revert" behavior - originally the run ID's own copy
 * button, now reused everywhere a value needs the same one-tap-copy affordance (e.g. a failure's
 * detail text). A second `copy()` call before the previous one's timer fires resets the revert
 * deadline rather than racing it - two independent timers would both eventually fire, and whichever
 * happened to fire last would arbitrarily decide the final (wrong, for the second call) `copied`
 * value.
 *
 * `requestId` is bumped both by a new `copy()` call and by unmount - either way, it invalidates any
 * still-in-flight `writeText` call: when that write eventually resolves, its own captured id no
 * longer matches `requestId.current`, so it is treated as stale and becomes a no-op rather than
 * calling `setState` on an unmounted component or scheduling a revert timer nothing will ever clear
 * (a real leak this hook once had - `copy()` called right before unmount, with the write still
 * pending when the cleanup effect ran, meant the cleanup could only clear a timer that did not exist
 * yet; the write resolving afterward would set state and schedule a fresh, un-cleared one).
 *
 * A rejected `navigator.clipboard.writeText` (permission denied, unsupported browser) is swallowed
 * silently: no app state depends on it succeeding, `copied` simply never flips to `true`, and the
 * caller can still select the text by hand.
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
        // See doc comment above - deliberately not surfaced.
      }
    })();
  }

  return { copied, copy };
}
