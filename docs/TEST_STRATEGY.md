# Risk-Based Test Strategy

## Context

Restful Booker Platform is a public, shared training environment. It resets its seeded data periodically and can evolve independently of this repository. The suite therefore separates read-only confidence checks from state-changing scenarios and keeps assertions focused on product risks rather than implementation coverage percentages.

## Risk map

| Risk | Best layer | Current mitigation | Next increment |
|---|---|---|---|
| Guests cannot discover inventory | API + UI | Room contract, UI smoke, parity journey | Availability filters and empty state |
| Admin authentication is unavailable or bypassable | API + UI | Valid/invalid credential smoke, room+booking authorization checks | Expired token and logout contracts |
| API and UI expose different inventory | Cross-layer | API-to-UI count parity | Compare room type, price, and features |
| Room management corrupts inventory | API | Opt-in CRUD lifecycle with cleanup | Validation and authorization matrix |
| Booking management corrupts inventory or leaks across guests | API | Opt-in CRUD lifecycle with cleanup, anonymous-access authorization checks | Cancellation and rescheduling rules |
| Conflicting bookings are accepted | API | Invalid ranges, zero-night stays, exact/partial overlap, adjacent-boundary coverage | Timezone and longer-stay cases |
| Guest cannot complete a booking | UI journey | End-to-end reservation journey with API cleanup | Multi-day stays, price summary assertions |
| Contact messages are lost or malformed | API/UI journey | API validation matrix, opt-in API and UI submission checks | Admin-side message visibility verification |
| Frontend excludes users with disabilities | UI | Accessible locators improve testability; a labelling defect on the contact form's message field was found and documented (see README) | axe-core WCAG gate |

## Layering policy

- Prefer API tests for validation rules, status codes, authorization, contracts, and combinatorial data.
- Use UI tests for rendering, navigation, wiring, accessibility roles, and a small set of critical journeys.
- Use cross-layer tests only where consistency between services and presentation is the risk.
- Do not duplicate every API scenario through the browser.

## Data policy

- Every test has exactly one effect tag: `read-only` or `mutation`; the JUnit extension validates this
  together with the layer, feature, and `regression` taxonomy.
- Read-only checks are the default and may run in CI.
- Accepted mutations use unique data and clean up whenever the target exposes a delete operation.
- Mutations are tagged `mutation`, serialized with a resource lock, and invoked explicitly.
- A rejected write is still classified as a mutation on the shared target: if validation regresses,
  the request can persist data before the assertion fails.
- Test-owned rooms and bookings use `AutoCloseable` managed resources so cleanup failures are
  retained as suppressed exceptions without hiding the original test failure.
- A future local Docker environment should become the primary target for destructive and high-volume suites.
- Tests never depend on a fixed room count or a record created by another test.

## Flake prevention

- Playwright web-first assertions replace sleeps and polling loops.
- Tests use a clean browser context and API context.
- Locators prefer roles, labels, and user-visible names.
- Assertions wait for the asynchronous room inventory before reading counts.
- No implicit waits and no global retry policy.

## Quality gates

A pull request should pass:

1. `spotlessCheck`
2. `testClasses`
3. The read-only `test` suite
4. CI artifact publication on failure

Mutation, performance, and security testing require an explicitly authorized environment and are not part of the public-host CI gate.
