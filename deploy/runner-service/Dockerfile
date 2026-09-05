# D0 spike image for runner-service. Deliberately a single stage, not the usual "slim multi-stage"
# split: the runtime environment here has to be able to launch real `./gradlew <task> --no-daemon`
# child processes on demand (see SuiteCommandFactory), so it needs the *entire* repository, the
# Gradle wrapper, and a warm dependency/Playwright-browser cache present at runtime anyway - there
# is no smaller "runtime-only" subset to split out to. Building the jar in this same image also
# doubles as pre-warming the Gradle wrapper distribution and dependency cache into an image layer,
# so the first real suite launch in a running container isn't also the first time Gradle downloads
# anything over the network.
#
# Only PUBLIC-environment suites are supported by this image - LOCAL needs the separate seven-
# container Restful Booker Platform stack (infra/rbp/), out of scope for the portfolio deployment
# (see docs/DEPLOYMENT_ARCHITECTURE.md).
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
# JAVA_TOOL_OPTIONS, not JAVA_OPTS - the JVM itself reads this env var directly (prints "Picked up
# JAVA_TOOL_OPTIONS" to stderr, harmless) with no shell expansion involved, which is what makes the
# exec-form CMD below possible at all - a shell-form `CMD java $JAVA_OPTS ...` would need /bin/sh to
# expand $JAVA_OPTS, which is exactly the PID-1-is-a-shell problem this replaces.
ENV JAVA_TOOL_OPTIONS="-Xmx256m"
# Overridden by docker-compose.yml to point at the persistent volume mount - kept here only as the
# same defaults application.yml already documents, not a real path decision made in this file.
ENV RUNNER_RAWEVENTSDIR=/data/runner-events/raw
ENV RUNNER_JOURNALDIR=/data/runner-events/journal
ENV RUNNER_LOGSDIR=/data/runner-logs
ENV RUNNER_ARTIFACTSDIR=/data/runner-artifacts

EXPOSE 8080

# Exec form deliberately: `java` itself becomes PID 1, so `docker stop`/`compose restart` deliver
# SIGTERM directly to the JVM - Spring's own graceful-shutdown/@PreDestroy hooks (already observed
# live in this project's own logs - "Commencing graceful shutdown") only run if the signal actually
# reaches java, which a shell-form CMD's intervening /bin/sh does not reliably forward.
CMD ["java", "-jar", "/app/runner-service.jar"]
