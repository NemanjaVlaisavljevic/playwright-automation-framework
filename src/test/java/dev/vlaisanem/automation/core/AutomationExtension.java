package dev.vlaisanem.automation.core;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import dev.vlaisanem.automation.api.ApiContextFactory;
import dev.vlaisanem.automation.config.TestConfig;
import java.util.Set;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.StoreScope;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

public final class AutomationExtension
    implements BeforeEachCallback, AfterTestExecutionCallback, ParameterResolver {
  private static final Namespace NAMESPACE = Namespace.create(AutomationExtension.class);
  private static final String FIXTURE_KEY = "test-fixture";
  private static final Set<String> LAYER_TAGS = Set.of("api", "ui", "journey");
  private static final Set<String> FEATURE_TAGS = Set.of("auth", "room", "booking", "message");
  private static final Set<String> EFFECT_TAGS = Set.of("read-only", "mutation");

  @Override
  public void beforeEach(ExtensionContext context) {
    validateTagTaxonomy(context);
    guardMutationAgainstSharedTarget(context);
    RuntimeRegistry registry =
        context
            .getStore(StoreScope.EXECUTION_REQUEST, NAMESPACE)
            .computeIfAbsent(RuntimeRegistry.class);
    context.getStore(NAMESPACE).put(FIXTURE_KEY, new TestFixture(TestConfig.current(), registry));
  }

  @Override
  public void afterTestExecution(ExtensionContext context) {
    if (context.getExecutionException().isPresent()) {
      fixture(context).captureFailure(context);
    }
  }

  @Override
  public boolean supportsParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext) {
    Class<?> type = parameterContext.getParameter().getType();
    return type == TestConfig.class
        || type == Page.class
        || type == BrowserContext.class
        || type == APIRequestContext.class
        || type == ApiContextFactory.class;
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext) {
    Class<?> type = parameterContext.getParameter().getType();
    TestFixture fixture = fixture(extensionContext);
    if (type == TestConfig.class) {
      return fixture.config();
    }
    if (type == Page.class) {
      return fixture.page();
    }
    if (type == BrowserContext.class) {
      fixture.page();
      return fixture.page().context();
    }
    if (type == APIRequestContext.class) {
      return fixture.api();
    }
    if (type == ApiContextFactory.class) {
      return fixture.apiContexts();
    }
    throw new IllegalArgumentException("Unsupported parameter type: " + type.getName());
  }

  private static TestFixture fixture(ExtensionContext context) {
    return context.getStore(NAMESPACE).get(FIXTURE_KEY, TestFixture.class);
  }

  private static void validateTagTaxonomy(ExtensionContext context) {
    Set<String> tags = context.getTags();
    requireExactlyOneTag(context, tags, "layer", LAYER_TAGS);
    requireExactlyOneTag(context, tags, "feature", FEATURE_TAGS);
    requireExactlyOneTag(context, tags, "effect", EFFECT_TAGS);
    if (!tags.contains("regression")) {
      throw new ExtensionConfigurationException(
          context.getRequiredTestClass().getSimpleName() + " must have the regression tag");
    }
  }

  /**
   * A {@code mutation}-tagged test writes to whatever {@link TestConfig#baseUrl()} points at. Run
   * against the shared target ({@link TestConfig#sharedTargetBaseUrl()}), that data is visible to
   * everyone else using it, so refuse unless the developer explicitly opts in.
   */
  private static void guardMutationAgainstSharedTarget(ExtensionContext context) {
    TestConfig config = TestConfig.current();
    if (context.getTags().contains("mutation")
        && config.targetsSharedEnvironment()
        && !config.allowMutationAgainstSharedTarget()) {
      throw new ExtensionConfigurationException(
          context.getRequiredTestClass().getSimpleName()
              + " is tagged mutation but the configured baseUrl ("
              + config.baseUrl()
              + ") is the shared target. Point baseUrl at a dedicated environment, or set "
              + "allowMutationAgainstSharedTarget/ALLOW_MUTATION_AGAINST_SHARED_TARGET=true to "
              + "opt in explicitly.");
    }
  }

  private static void requireExactlyOneTag(
      ExtensionContext context, Set<String> actualTags, String category, Set<String> allowedTags) {
    long matches = allowedTags.stream().filter(actualTags::contains).count();
    if (matches != 1) {
      throw new ExtensionConfigurationException(
          context.getRequiredTestClass().getSimpleName()
              + " must have exactly one "
              + category
              + " tag from "
              + allowedTags
              + ", but had "
              + actualTags);
    }
  }
}
