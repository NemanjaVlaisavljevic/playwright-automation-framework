/** Joins truthy class names with a space - a hand-rolled `clsx`, not worth a dependency for this. */
export function cx(
  ...classes: Array<string | false | undefined | null>
): string {
  return classes.filter((value): value is string => Boolean(value)).join(" ");
}
