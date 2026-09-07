# Production image for runner-service (Faza D1 - built and run as a real deployment artifact, not
# a throwaway spike). Deliberately a single stage, not the usual "slim multi-stage" split: the
# runtime environment here has to be able to launch real `./gradlew <task> --no-daemon` child
# processes on demand (see SuiteCommandFactory), so it needs the *entire* repository, the Gradle
# wrapper, and a warm dependency/Playwright-browser cache present at runtime anyway - there is no
# smaller "runtime-only" subset to split out to. Building the jar in this same image also doubles
# as pre-warming the Gradle wrapper distribution and dependency cache into an image layer, so the
# first real suite launch in a running container isn't also the first time Gradle downloads
# anything over the network.
#
# Only PUBLIC-environment suites are supported by this image - LOCAL needs the separate seven-
# container Restful Booker Platform stack (infra/rbp/), out of scope for the portfolio deployment
# (see docs/DEPLOYMENT_ARCHITECTURE.md). Enforced here, not just by omission: RUNNER_DEPLOYMENTPROFILE
# below defaults this image itself to PORTFOLIO (fail-closed) regardless of how it is launched -
# see that ENV's own comment for why docker-compose.yml setting the same value again is not
# redundant.
FROM eclipse-temurin:21-jdk-jammy

WORKDIR /app

# Copy everything needed to evaluate the Gradle build (settings.gradle/build.gradle read
# infra/rbp/upstream.properties unconditionally at configuration time, even though localSut* tasks
# themselves are never invoked here - see build.gradle's own top-level `file(...).withInputStream`
# call) and to run the actual test suites (src/test/java, the model/api/support/core packages under
# it, schemas, resources).
COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle gradle
COPY runner-contract runner-contract
COPY runner-listener runner-listener
COPY runner-service runner-service
COPY src/test src/test
COPY infra/rbp infra/rbp

RUN chmod +x gradlew \
    # --with-deps installs Chromium's own OS-level shared libraries via apt (needs root, which we
    # already are at image-build time) - the same call this repo's own CI workflows make on
    # ubuntu-latest runners (see .github/workflows/dashboard-e2e.yml/local-sut.yml).
    && ./gradlew playwrightInstall -PwithDeps=true --console=plain \
    && ./gradlew :runner-service:bootJar --console=plain \
    # Copied to a version-independent, fixed name - `runner-service/build.gradle`'s own `version =
    # '1.0.0-SNAPSHOT'` will change once Faza D's own versioning work lands (see
    # docs/RELEASE_CANDIDATE.md's C5.6 section), and CMD below must not silently break the moment
    # it does.
    && cp runner-service/build/libs/runner-service-*.jar /app/runner-service.jar \
    # Gradle's own build cache/daemon files are single-machine-instance scoped and not useful to
    # keep in a shipped image layer - trimmed to keep the image leaner without touching anything the
    # runtime path (gradlew --no-daemon, launched fresh per run) actually needs.
    && rm -rf /root/.gradle/daemon /root/.gradle/caches/*/generated-gradle-jars

ENV SERVER_ADDRESS=0.0.0.0
# D3.2 - this service sits behind Caddy's TLS termination, so a request that reaches it has already
# been decrypted and re-sent as plain HTTP; without this, Spring Security's OAuth2 {baseUrl}
# resolution (used for the redirect_uri sent to GitHub) would see that internal http:// hop and
# build the wrong scheme, never matching the https:// callback registered with the GitHub OAuth
# App. FRAMEWORK makes Spring trust X-Forwarded-Proto/X-Forwarded-Host (which Caddy's reverse_proxy
# sets by default) to recover the real public https://<domain> the request actually arrived on.
# Safe only because runner-service is never reachable except through Caddy (no published port -
# see docker-compose.yml) - Caddy is the sole edge that can ever set these headers here.
ENV SERVER_FORWARD_HEADERS_STRATEGY=framework
# JAVA_TOOL_OPTIONS, not JAVA_OPTS - the JVM itself reads this env var directly (prints "Picked up
# JAVA_TOOL_OPTIONS" to stderr, harmless) with no shell expansion involved, which is what makes the
# exec-form CMD below possible at all - a shell-form `CMD java $JAVA_OPTS ...` would need /bin/sh to
# expand $JAVA_OPTS, which is exactly the PID-1-is-a-shell problem this replaces.
ENV JAVA_TOOL_OPTIONS="-Xmx256m"
# Overridden by docker-compose.yml to point at the persistent volume mount - kept here only as the
# same defaults application.yml already documents, not a real path decision made in this file.
ENV RUNNER_RAWEVENTSDIR=/data/runner-events/raw
ENV RUNNER_LOGSDIR=/data/runner-logs
ENV RUNNER_ARTIFACTSDIR=/data/runner-artifacts
# Fail-closed default: this image only supports PUBLIC (see the header comment above), so it must
# never fall back to application.yml's own permissive LOCAL_DEV default (which advertises/accepts
# LOCAL too) just because some future launch path - a bare `docker run`, a different orchestrator -
# forgets to repeat docker-compose.yml's own RUNNER_DEPLOYMENTPROFILE=PORTFOLIO. Local `bootRun`/
# tests never go through this Dockerfile at all, so they are unaffected and keep using LOCAL_DEV.
# docker-compose.yml setting the identical value again is deliberate belt-and-braces documentation,
# not redundant - it is the one place a human reads to see this deployment's actual profile without
# having to already know this image's own fail-closed default.
ENV RUNNER_DEPLOYMENTPROFILE=PORTFOLIO
# D3.2 - the session cookie can only carry Secure over a real HTTPS origin (browsers refuse to send
# a Secure cookie over plain HTTP), which is true here (Caddy terminates TLS in front of this
# service) but not for a local `http://127.0.0.1` bootRun - so this default lives here, not in
# application.yml, mirroring the SERVER_ADDRESS/RUNNER_DEPLOYMENTPROFILE deployment-only-override
# pattern already used above.
ENV SERVER_SERVLET_SESSION_COOKIE_SECURE=true

EXPOSE 8080

# Exec form deliberately: with docker-compose.yml's `init: true` on this service, Docker's own
# minimal init (tini) becomes PID 1 and execs this CMD as its child - `java` is PID 2, not PID 1,
# but init forwards SIGTERM straight through to it (Spring's own graceful-shutdown/@PreDestroy
# hooks, already observed live in this project's own logs - "Commencing graceful shutdown" - still
# fire) and additionally reaps any orphaned grandchild (a Gradle `--no-daemon` build, a forked JUnit
# worker, Chromium's own helper processes) that outlives its immediate parent during a forced-kill
# path - java alone is not a real init/reaper for processes it never directly re-parents to itself.
# A shell-form `CMD java $JAVA_OPTS ...` would still be wrong even under `init: true`: /bin/sh as
# init's child would itself need to forward the signal to java, which it does not do reliably.
CMD ["java", "-jar", "/app/runner-service.jar"]
