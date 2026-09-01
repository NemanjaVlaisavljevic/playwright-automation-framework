import styles from "./LoadingSkeleton.module.css";

export interface LoadingSkeletonProps {
  /** Number of placeholder lines to render. */
  lines?: number;
  /** A fixed height/line for anything that isn't text (a card, a badge) - CSS length string. */
  height?: string;
}

/**
 * Purely decorative - the actual "loading" announcement for assistive tech is the surrounding
 * `aria-live`/status text a page already renders (e.g. "Loading run…"), so this is `aria-hidden`
 * rather than duplicating that announcement itself.
 */
export function LoadingSkeleton({ lines = 3, height }: LoadingSkeletonProps) {
  return (
    <div className={styles.skeleton} aria-hidden="true">
      {Array.from({ length: lines }, (_, index) => (
        <div
          key={index}
          className={styles.line}
          style={height !== undefined ? { height } : undefined}
        />
      ))}
    </div>
  );
}
