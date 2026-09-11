# Production image for runner-service. Deliberately a single stage, not the usual "slim
# multi-stage" split: the runtime environment has to launch real `./gradlew <task> --no-daemon`
# child processes on demand (see SuiteCommandFactory), so it needs the entire repository, the
# Gradle wrapper, and a warm dependency/Playwright-browser cache present at runtime anyway - there
# is no smaller "runtime-only" subset to split out. Building the jar in this same image also
# pre-warms the Gradle wrapper distribution and dependency cache into an image layer, so the first
# real suite launch isn't also the first time Gradle downloads anything over the network.
#
# Only PUBLIC-environment suites are supported by this image - LOCAL needs the separate seven-
# container Restful Booker Platform stack (infra/rbp/), out of scope for this deployment (see
# docs/DEPLOYMENT_ARCHITECTURE.md). RUNNER_DEPLOYMENTPROFILE below defaults this image to
# PORTFOLIO (fail-closed) regardless of how it is launched.
FROM eclipse-temurin:21-jdk-jammy

WORKDIR /app

# The base image has neither curl nor wget by default; needed for docker-compose.yml's healthcheck
# (a plain HTTP GET against /actuator/health/readiness). Its own early layer, before the
# COPY/Gradle-build steps below, so it caches independently of source changes.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Everything needed to evaluate the Gradle build (settings.gradle/build.gradle read
# infra/rbp/upstream.properties unconditionally at configuration time, even though localSut* tasks
# are never invoked here) and to run the actual test suites.
COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle gradle
COPY runner-contract runner-contract
COPY runner-listener runner-listener
COPY runner-service runner-service
COPY src/test src/test
COPY infra/rbp infra/rbp

RUN chmod +x gradlew \
    # --with-deps installs Chromium's OS-level shared libraries via apt (needs root, which we
    # already are at image-build time) - same call this repo's CI workflows make on ubuntu-latest.
    && ./gradlew playwrightInstall -PwithDeps=true --console=plain \
    && ./gradlew :runner-service:bootJar --console=plain \
    # Copied to a version-independent, fixed name so CMD below doesn't break if the Gradle
    # project's own version string changes.
    && cp runner-service/build/libs/runner-service-*.jar /app/runner-service.jar \
    # Gradle's build cache/daemon files are single-machine-instance scoped and not useful to keep
    # in a shipped image layer.
    && rm -rf /root/.gradle/daemon /root/.gradle/caches/*/generated-gradle-jars

ENV SERVER_ADDRESS=0.0.0.0
# This service sits behind Caddy's TLS termination, so a request that reaches it has already been
# decrypted and re-sent as plain HTTP; without this, Spring Security's OAuth2 {baseUrl} resolution
# would build the wrong scheme for the redirect_uri sent to GitHub. NATIVE activates Tomcat's own
# RemoteIpValve instead of a plain ForwardedHeaderFilter - the valve only honors
# X-Forwarded-Proto/-Host/-For when the direct TCP peer matches
# server.tomcat.remoteip.internal-proxies (default already covers private/Docker-internal ranges),
# whereas ForwardedHeaderFilter has no such trust boundary and would parse those headers
# unconditionally regardless of sender. AbuseRateLimitFilter's client-IP-keyed rate limits depend on
# this trust boundary. Safe only because runner-service is never reachable except through Caddy (no
# published port), and Caddy's own container IP is always within the trusted range.
ENV SERVER_FORWARD_HEADERS_STRATEGY=native
# JAVA_TOOL_OPTIONS, not JAVA_OPTS - the JVM reads this env var directly with no shell expansion
# involved, which is what makes the exec-form CMD below possible (a shell-form `CMD java $JAVA_OPTS
# ...` would need /bin/sh to expand it, reintroducing the PID-1-is-a-shell problem this avoids).
ENV JAVA_TOOL_OPTIONS="-Xmx256m"
# Overridden by docker-compose.yml to point at the persistent volume mount - kept here only as the
# same defaults application.yml already documents.
ENV RUNNER_RAWEVENTSDIR=/data/runner-events/raw
ENV RUNNER_LOGSDIR=/data/runner-logs
ENV RUNNER_ARTIFACTSDIR=/data/runner-artifacts
# Fail-closed default: this image only supports PUBLIC, so it must never fall back to
# application.yml's own permissive LOCAL_DEV default (which also accepts LOCAL) just because some
# future launch path forgets to repeat docker-compose.yml's RUNNER_DEPLOYMENTPROFILE=PORTFOLIO.
# Local `bootRun`/tests never go through this Dockerfile, so they keep using LOCAL_DEV unaffected.
ENV RUNNER_DEPLOYMENTPROFILE=PORTFOLIO
# The session cookie can only carry Secure over a real HTTPS origin, true here (Caddy terminates
# TLS in front of this service) but not for a local `http://127.0.0.1` bootRun - so this default
# lives here, not in application.yml.
ENV SERVER_SERVLET_SESSION_COOKIE_SECURE=true

# Elastic Common Schema (ECS) JSON logging, via Spring Boot's own native structured-logging
# support - scoped to this deployment only; local `bootRun`/tests never set these, so Spring Boot's
# plain human-readable console pattern applies there unchanged. ECS automatically folds in MDC
# entries and SLF4J fluent-API key-value pairs into real JSON fields with no extra wiring needed.
#
# ARG must be declared after FROM (one declared before it is only visible to FROM itself) and
# immediately before the ENV line that references it. docker-compose.yml's runner-service service
# passes this through via `build.args.APP_VERSION` - a runtime `environment:` override cannot
# change it; it's baked in at build time.
ARG APP_VERSION=unknown
ENV LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs
ENV LOGGING_STRUCTURED_ECS_SERVICE_NAME=runner-service
ENV LOGGING_STRUCTURED_ECS_SERVICE_VERSION=${APP_VERSION}
ENV LOGGING_STRUCTURED_ECS_SERVICE_ENVIRONMENT=portfolio

EXPOSE 8080

# Exec form deliberately: with docker-compose.yml's `init: true`, Docker's own minimal init (tini)
# becomes PID 1 and execs this CMD as its child - `java` is PID 2, but init forwards SIGTERM
# straight through (Spring's graceful-shutdown/@PreDestroy hooks still fire) and reaps any orphaned
# grandchild process (a Gradle `--no-daemon` build, a forked JUnit worker, Chromium's helper
# processes). A shell-form `CMD java $JAVA_OPTS ...` would still be wrong even under `init: true`:
# /bin/sh as init's child does not forward the signal to java reliably.
CMD ["java", "-jar", "/app/runner-service.jar"]
