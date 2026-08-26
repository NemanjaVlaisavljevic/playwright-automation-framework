# Contributing

## Local quality gate

```bash
./gradlew spotlessApply test
```

Run `mutationTest` only when you intentionally want to change shared sandbox data.

## Test conventions

- Name tests as observable behavior, not implementation methods.
- Add `@AutomationTest`, `regression`, exactly one layer tag (`api`, `ui`, or `journey` — a cross-layer
  test is `journey` only, never `journey` plus `api`/`ui`), one feature tag (`auth`, `room`, `booking`,
  or `message`), exactly one effect tag (`read-only` or `mutation`), and `smoke` if it belongs in the
  critical-path suite. `AutomationExtension` rejects tests that violate this taxonomy.
- Keep assertions in tests unless a page object is asserting its own load contract.
- Prefer role/label locators. Use `data-testid` only where no stable user-facing contract exists.
- Never add `Thread.sleep`, implicit waits, production credentials, or assertion-free tests.
- API clients return `ApiResult`; they do not silently accept non-success responses.
- Any request that can change state must use `mutation`, including negative writes that are expected
  to be rejected. Test-owned resources must attempt cleanup whenever the API supports it.
- A bug workaround must link to an issue and make the accepted behavior explicit.

## Formatting

Spotless applies Google Java Format and whitespace checks:

```bash
./gradlew spotlessApply
./gradlew spotlessCheck
```
