package dev.vlaisanem.automation.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.TagFilter;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * Generates the server-side allowlist catalog of tests safe to launch individually against {@code
 * PUBLIC} - the {@code CUSTOM} suite (D0.5) never accepts a Gradle task, tag, class, or {@code
 * --tests} expression from a client; it only ever accepts one of the {@code testKey}s this
 * generator itself discovered and wrote out. Discovery-only - no test in the catalog is executed by
 * running this class.
 *
 * <p>Deliberately excludes {@code mutation} (never safe to launch individually against the shared
 * public target) and {@code fixture} ({@code StepDrilldownFixtureTest}/{@code
 * CancelDuringStepFixtureTest} are platform self-tests with their own dedicated, always-available
 * {@code FIXTURE} suite - see {@code CLAUDE.md} - not meant to appear alongside real feature tests
 * in a picker). Every remaining test still carries {@code regression} unconditionally (enforced by
 * {@link dev.vlaisanem.automation.core.AutomationExtension}), so filtering on it is equivalent to
 * "every real, non-mutation, non-fixture automation test" without needing a separate allowlist of
 * packages to scan.
 *
 * <p>Everything below the discovery call itself is a fail-fast integrity check on what was
 * discovered, not just a trust-the-filter pass-through - this generator is the one place that
 * decides what a client is ever allowed to launch individually, so a JUnit Platform surprise (a
 * filter behaving unexpectedly, a test carrying an unexpected tag combination) must abort the build
 * loudly rather than silently produce a subtly-wrong catalog:
 *
 * <ul>
 *   <li>every discovered test must have a {@link MethodSource} - this project has no
 *       parameterized/dynamic tests today (verified:
 *       {@code @ParameterizedTest}/{@code @TestFactory}/{@code @RepeatedTest} appear nowhere under
 *       {@code tests/}), so a plain class+method pair is a complete, stable selection unit; a
 *       future parameterized test needs this generator extended (one catalog entry selecting every
 *       invocation), not silently miscounted;
 *   <li>every {@code testKey} must be unique - two overloaded methods of the same name would
 *       otherwise collide onto the same key, and {@code CustomTestSelectionValidator}'s lookup map
 *       would silently keep only one while Gradle's own {@code --tests Class.method} filter could
 *       still match both;
 *   <li>every entry must carry {@code regression} and {@code read-only}, and neither {@code
 *       mutation} nor {@code fixture} - re-asserted explicitly here, not just trusted from the
 *       {@link TagFilter} passed to the {@link Launcher}, since a filter is exactly the kind of
 *       thing that can be misconfigured without a compiler catching it;
 *   <li>every entry must carry <em>exactly</em> one of the {@code api}/{@code ui}/{@code journey}
 *       layer tags - {@link dev.vlaisanem.automation.core.AutomationExtension} already enforces
 *       this at test-execution time, but this generator runs at build time, before that extension
 *       ever gets a chance to reject anything.
 * </ul>
 */
public final class TestCatalogGenerator {

  private static final Set<String> LAYER_TAGS = Set.of("api", "ui", "journey");

  private TestCatalogGenerator() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      throw new IllegalArgumentException(
          "Usage: TestCatalogGenerator <output-file> (got " + args.length + " argument(s))");
    }
    Path outputPath = Path.of(args[0]);

    List<PublicTestCatalogEntry> entries = discover();

    Files.createDirectories(outputPath.getParent());
    ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    String json = mapper.writeValueAsString(new PublicTestCatalog(entries));
    // A trailing newline, like every other checked-in JSON file in this repo (schemas, the OpenAPI
    // snapshot) - Jackson's own pretty-printer does not add one.
    Files.writeString(outputPath, json + System.lineSeparator(), StandardCharsets.UTF_8);

    System.out.println(
        "TestCatalogGenerator: wrote " + entries.size() + " test(s) to " + outputPath);
  }

  static List<PublicTestCatalogEntry> discover() {
    LauncherDiscoveryRequest request =
        LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectPackage("dev.vlaisanem.automation.tests"))
            .filters(
                TagFilter.includeTags("regression"), TagFilter.excludeTags("mutation", "fixture"))
            .build();

    List<PublicTestCatalogEntry> entries = new ArrayList<>();
    // try-with-resources: LauncherSession owns the discovered TestEngines' native resources -
    // closing it is what actually releases them, matching the Launcher API's own documented usage
    // (this is a one-shot discovery process, not a long-lived launcher).
    try (LauncherSession session = LauncherFactory.openSession()) {
      Launcher launcher = session.getLauncher();
      TestPlan plan = launcher.discover(request);
      for (TestIdentifier root : plan.getRoots()) {
        collect(plan, root, entries);
      }
    }

    entries.sort(Comparator.comparing(PublicTestCatalogEntry::testKey));
    requireUniqueTestKeys(entries);
    return entries;
  }

  private static void collect(
      TestPlan plan, TestIdentifier identifier, List<PublicTestCatalogEntry> out) {
    if (identifier.isTest()) {
      out.add(toEntry(identifier));
    }
    for (TestIdentifier child : plan.getChildren(identifier)) {
      collect(plan, child, out);
    }
  }

  private static PublicTestCatalogEntry toEntry(TestIdentifier identifier) {
    MethodSource source =
        identifier
            .getSource()
            .filter(MethodSource.class::isInstance)
            .map(MethodSource.class::cast)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Test "
                            + identifier.getUniqueId()
                            + " has no MethodSource - parameterized/dynamic tests are not yet"
                            + " supported by the CUSTOM catalog generator, see this class's own"
                            + " Javadoc"));

    String testKey = source.getClassName() + "#" + source.getMethodName();
    // TreeSet, not the identifier's own reported iteration order - a JUnit-internal Set
    // implementation detail must never be what decides this committed file's own byte-for-byte
    // stability from one discovery run to the next.
    Set<String> tags = new TreeSet<>();
    identifier.getTags().forEach(tag -> tags.add(tag.getName()));

    requireTag(testKey, tags, "regression");
    requireTag(testKey, tags, "read-only");
    forbidTag(testKey, tags, "mutation");
    forbidTag(testKey, tags, "fixture");

    List<String> layers = LAYER_TAGS.stream().filter(tags::contains).toList();
    if (layers.size() != 1) {
      throw new IllegalStateException(
          "Test "
              + testKey
              + " has "
              + layers.size()
              + " of the api/ui/journey layer tags (expected exactly 1): "
              + layers);
    }

    return new PublicTestCatalogEntry(
        testKey, identifier.getDisplayName(), layers.get(0).toUpperCase(Locale.ROOT), tags);
  }

  private static void requireTag(String testKey, Set<String> tags, String tag) {
    if (!tags.contains(tag)) {
      throw new IllegalStateException(
          "Test " + testKey + " is missing the required '" + tag + "' tag: " + tags);
    }
  }

  private static void forbidTag(String testKey, Set<String> tags, String tag) {
    if (tags.contains(tag)) {
      throw new IllegalStateException(
          "Test "
              + testKey
              + " carries the forbidden '"
              + tag
              + "' tag - TagFilter should already have excluded it: "
              + tags);
    }
  }

  private static void requireUniqueTestKeys(List<PublicTestCatalogEntry> entries) {
    Map<String, String> seenDisplayNameByKey = new HashMap<>();
    for (PublicTestCatalogEntry entry : entries) {
      String previousDisplayName =
          seenDisplayNameByKey.putIfAbsent(entry.testKey(), entry.displayName());
      if (previousDisplayName != null) {
        throw new IllegalStateException(
            "Duplicate testKey '"
                + entry.testKey()
                + "' - likely two overloaded methods with the same name in the same class. Both"
                + " '"
                + previousDisplayName
                + "' and '"
                + entry.displayName()
                + "' would collide onto one catalog entry.");
      }
    }
  }
}
