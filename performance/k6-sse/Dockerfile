# D4.4.1c - a custom k6 build with the xk6-sse extension baked in (stock k6 has no native SSE
# support - confirmed by reading its own module list). Every version pinned as precisely as this
# actually gets - never `latest` anywhere in this file:
#   - k6 v1.5.0 - the exact same version performance/k6's own stock `grafana/k6:1.5.0` service
#     uses, locked together with performance/k6/lib/summary.js's own output-format assumptions
#     (k6 1.5 introduced a new machine-readable summary format that becomes default in k6 2).
#   - xk6 v0.13.4 - the build tool itself.
#   - xk6-sse v0.1.12 - a real, tagged release (verified: `go get
#     github.com/phymbert/xk6-sse@latest` resolves to this exact version), not a floating branch/
#     commit reference. Go's own module system additionally verifies this content against the
#     public checksum database (sum.golang.org) the first time any consumer anywhere fetches it,
#     so a later attempt to silently move this tag to different content would itself be detected
#     - not a repo-local guarantee, but a real one.
#   - Both base images (`golang`/`alpine`) are pinned by their exact, current content digest
#     (`@sha256:...`, not just a version tag) - a tag alone (e.g. `golang:1.25-alpine`) can start
#     resolving to different content on a later pull (a patch rebuild of the same tag); a digest
#     cannot.
#
# **Precisely what this does and does not guarantee** (corrected from an earlier draft that
# overclaimed this): pinning every version above, including by digest, makes *running* the already-
# built image fully offline and reproducible - no network access, no version resolution, at
# test-run time. The *build* itself is not offline - `go install`/`xk6 build` still fetch Go module
# dependencies over the network - and is not guaranteed byte-for-byte identical across rebuilds
# (Go's own module resolution for xk6-sse's transitive dependencies is checksum-verified per
# module, but a rebuild is not guaranteed to reproduce the exact same compiled binary). A custom
# pre-built image is used here to make the *test-run* step offline/reproducible, not because there
# is no other way to add extensions to k6, and not because the *build* itself is offline. xk6-sse
# is a community-maintained extension, not audited or maintained by Grafana itself (Grafana's own
# extension-registry guidance is explicit about this) - pinned precisely for exactly that reason,
# not out of general caution alone.
#
# Multi-stage: the build stage needs the full Go toolchain + git; the runtime image is just the
# resulting static-ish binary plus CA certificates (no outbound TLS is actually needed against
# this project's own Compose network, but harmless to keep for a general-purpose k6 binary).
# golang:1.25-alpine, digest as of 2026-09-08.
FROM golang@sha256:1ae0735f00daffa3aaf1363a5184c0d2dc55c78e3db4ec70241cdac97bf84b59 AS build
RUN apk add --no-cache git build-base
RUN go install go.k6.io/xk6/cmd/xk6@v0.13.4
WORKDIR /build
RUN /go/bin/xk6 build v1.5.0 --with github.com/phymbert/xk6-sse@v0.1.12 --output /build/k6

# alpine:3.20, digest as of 2026-09-08.
FROM alpine@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc
RUN apk add --no-cache ca-certificates
COPY --from=build /build/k6 /usr/bin/k6
ENTRYPOINT ["k6"]
