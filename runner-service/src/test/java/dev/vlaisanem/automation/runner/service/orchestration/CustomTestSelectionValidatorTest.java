package dev.vlaisanem.automation.runner.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vlaisanem.automation.runner.service.catalog.TestCatalogEntry;
import dev.vlaisanem.automation.runner.service.domain.SelectedTestSnapshot;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.domain.TestLayer;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CustomTestSelectionValidatorTest {

  private static final TestCatalogEntry ENTRY_A =
      new TestCatalogEntry("some.Test#a", "Test A", TestLayer.API, Set.of("regression"));
  private static final TestCatalogEntry ENTRY_B =
      new TestCatalogEntry("some.Test#b", "Test B", TestLayer.UI, Set.of("regression"));
  private static final List<TestCatalogEntry> CATALOG = List.of(ENTRY_A, ENTRY_B);

  @Test
  void nonCustomWithNoTestKeysReturnsAnEmptySelection() {
    assertThat(CustomTestSelectionValidator.validate(Suite.SMOKE, null, CATALOG)).isEmpty();
    assertThat(CustomTestSelectionValidator.validate(Suite.SMOKE, List.of(), CATALOG)).isEmpty();
  }

  @Test
  void nonCustomWithTestKeysIsRejected() {
    assertThatThrownBy(
            () ->
                CustomTestSelectionValidator.validate(
                    Suite.SMOKE, List.of(ENTRY_A.testKey()), CATALOG))
        .isInstanceOf(InvalidTestSelectionException.class)
        .hasMessageContaining("SMOKE must not carry testKeys");
  }

  @Test
  void customWithNoTestKeysIsRejected() {
    assertThatThrownBy(() -> CustomTestSelectionValidator.validate(Suite.CUSTOM, null, CATALOG))
        .isInstanceOf(InvalidTestSelectionException.class)
        .hasMessageContaining("at least one testKey");
    assertThatThrownBy(
            () -> CustomTestSelectionValidator.validate(Suite.CUSTOM, List.of(), CATALOG))
        .isInstanceOf(InvalidTestSelectionException.class)
        .hasMessageContaining("at least one testKey");
  }

  @Test
  void customRejectsDuplicateTestKeys() {
    assertThatThrownBy(
            () ->
                CustomTestSelectionValidator.validate(
                    Suite.CUSTOM, List.of(ENTRY_A.testKey(), ENTRY_A.testKey()), CATALOG))
        .isInstanceOf(InvalidTestSelectionException.class)
        .hasMessageContaining("Duplicate testKey");
  }

  @Test
  void customRejectsATestKeyNotInTheCatalog() {
    assertThatThrownBy(
            () ->
                CustomTestSelectionValidator.validate(
                    Suite.CUSTOM, List.of("some.Test#doesNotExist"), CATALOG))
        .isInstanceOf(InvalidTestSelectionException.class)
        .hasMessageContaining("Unknown or stale testKey");
  }

  @Test
  void customRejectsMoreThanTwentyFiveEvenWithALargerCatalog() {
    List<TestCatalogEntry> bigCatalog =
        IntStream.range(0, 30)
            .mapToObj(
                i ->
                    new TestCatalogEntry(
                        "some.Test#m" + i, "Test " + i, TestLayer.API, Set.of("regression")))
            .toList();
    List<String> tooMany = IntStream.range(0, 26).mapToObj(i -> "some.Test#m" + i).toList();

    assertThatThrownBy(
            () -> CustomTestSelectionValidator.validate(Suite.CUSTOM, tooMany, bigCatalog))
        .isInstanceOf(InvalidTestSelectionException.class)
        .hasMessageContaining("At most 25");
  }

  @Test
  void customAllowsSelectingTheWholeCatalogWhenItIsSmallerThanTwentyFive() {
    List<String> allKeys = List.of(ENTRY_A.testKey(), ENTRY_B.testKey());

    List<SelectedTestSnapshot> selected =
        CustomTestSelectionValidator.validate(Suite.CUSTOM, allKeys, CATALOG);

    assertThat(selected).hasSize(2);
  }

  @Test
  void customBuildsSnapshotsFromTheCatalogNotJustTheRawKey() {
    List<SelectedTestSnapshot> selected =
        CustomTestSelectionValidator.validate(Suite.CUSTOM, List.of(ENTRY_A.testKey()), CATALOG);

    assertThat(selected)
        .containsExactly(
            new SelectedTestSnapshot(ENTRY_A.testKey(), ENTRY_A.displayName(), ENTRY_A.category()));
  }

  /**
   * Regression test for a review's finding: the map-building step used to overwrite a duplicate
   * catalog {@code testKey} silently (last one wins) instead of failing fast - defense-in-depth for
   * a corrupt/hand-edited catalog reaching this far despite {@code TestCatalogContentValidator}
   * already guarding {@code TestCatalogService} against exactly this.
   */
  @Test
  void failsFastOnADuplicateTestKeyInTheCatalogItselfInsteadOfSilentlyKeepingOne() {
    TestCatalogEntry duplicateOfA =
        new TestCatalogEntry(
            ENTRY_A.testKey(), "Duplicate of A", TestLayer.API, Set.of("regression"));
    List<TestCatalogEntry> catalogWithDuplicate = List.of(ENTRY_A, duplicateOfA, ENTRY_B);

    assertThatThrownBy(
            () ->
                CustomTestSelectionValidator.validate(
                    Suite.CUSTOM, List.of(ENTRY_A.testKey()), catalogWithDuplicate))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("duplicate testKey");
  }

  @Test
  void customPreservesTheRequestedOrderNotTheCatalogOrder() {
    List<SelectedTestSnapshot> selected =
        CustomTestSelectionValidator.validate(
            Suite.CUSTOM, List.of(ENTRY_B.testKey(), ENTRY_A.testKey()), CATALOG);

    assertThat(selected)
        .extracting(SelectedTestSnapshot::testKey)
        .containsExactly(ENTRY_B.testKey(), ENTRY_A.testKey());
  }
}
