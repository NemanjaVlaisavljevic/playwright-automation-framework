package dev.vlaisanem.automation.runner;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.service.artifacts.ArtifactRepository;
import dev.vlaisanem.automation.runner.service.disk.DiskUsageService;
import dev.vlaisanem.automation.runner.service.repository.RunLifecycleStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Locks the generated {@code /v3/api-docs} document against what the frontend's typed-client
 * generator depends on - verified via a real HTTP call against the running application, not a
 * static read of the JSON, so a backend refactor that breaks the frontend contract fails here.
 */
// Full context without a real Postgres: DataSourceAutoConfiguration must stay enabled (the
// readiness group needs a real `db` health contributor), Flyway is excluded (needs a real,
// migrated schema), and hikari.initialization-fail-timeout=-1 stops HikariCP's own startup
// connection check from failing context refresh when nothing is listening on the datasource URL.
// The @MockitoBean fields below replace JdbcRunStore/JdbcArtifactRepository's real beans so
// neither needs a reachable DataSource either.
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
      "spring.datasource.hikari.initialization-fail-timeout=-1"
    })
class OpenApiContractTest {

  @MockitoBean private RunLifecycleStore lifecycleStore;
  @MockitoBean private ArtifactRepository artifactRepository;
  // DiskUsageService also needs a JdbcTemplate and isn't behind an interface, so it's mocked here
  // for the same reason as the two stores above.
  @MockitoBean private DiskUsageService diskUsageService;

  private static final List<String> EXPECTED_OPERATION_IDS =
      List.of(
          "createRun",
          "listRuns",
          "getRun",
          "cancelRun",
          "downloadRunLog",
          "getRunnerCapabilities",
          "listRunArtifacts",
          "downloadRunArtifact",
          "listPublicTests");

  @Value("${local.server.port}")
  private int port;

  private JsonNode spec;

  @BeforeEach
  void fetchSpec() throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    spec = new ObjectMapper().readTree(response.body());
  }

  @Test
  void documentMetadataIsExplicitNotAPlaceholder() {
    JsonNode info = spec.path("info");
    assertThat(info.path("title").asText()).isEqualTo("Playwright Automation Runner API");
    assertThat(info.path("version").asText()).isEqualTo("v1");
    assertThat(info.path("description").asText()).isNotBlank();
  }

  @Test
  void everyOperationHasOneOfTheExpectedStableIds() {
    assertThat(allOperationIds()).containsExactlyInAnyOrderElementsOf(EXPECTED_OPERATION_IDS);
  }

  @Test
  void noTwoOperationsShareAnOperationId() {
    List<String> ids = allOperationIds();
    assertThat(ids).doesNotHaveDuplicates();
  }

  /**
   * One record per operation: doubles as the exact-response-code matrix and success media-type
   * expectations, so removing an {@code @ApiResponse} fails here directly instead of only narrowing
   * what {@link #everyDocumentedErrorResponseUsesProblemJsonAndTheProblemDetailSchema} sees.
   */
  private record OperationContract(
      String path,
      String method,
      List<String> expectedCodes,
      Map<String, String> successMediaTypes) {}

  private static final List<OperationContract> OPERATION_CONTRACTS =
      List.of(
          new OperationContract(
              "/api/v1/runs",
              "post",
              List.of("202", "400", "503", "500"),
              Map.of("202", MediaType.APPLICATION_JSON_VALUE)),
          new OperationContract(
              "/api/v1/runs",
              "get",
              List.of("200", "500"),
              Map.of("200", MediaType.APPLICATION_JSON_VALUE)),
          new OperationContract(
              "/api/v1/runs/{runId}",
              "get",
              List.of("200", "404", "500"),
              Map.of("200", MediaType.APPLICATION_JSON_VALUE)),
          new OperationContract(
              "/api/v1/runs/{runId}/cancel",
              "post",
              List.of("200", "404", "503", "500"),
              Map.of("200", MediaType.APPLICATION_JSON_VALUE)),
          new OperationContract(
              "/api/v1/runs/{runId}/log",
              "get",
              List.of("200", "404", "500"),
              Map.of("200", MediaType.TEXT_PLAIN_VALUE)),
          new OperationContract(
              "/api/v1/capabilities",
              "get",
              List.of("200", "500"),
              Map.of("200", MediaType.APPLICATION_JSON_VALUE)),
          new OperationContract(
              "/api/v1/runs/{runId}/artifacts",
              "get",
              List.of("200", "404", "500"),
              Map.of("200", MediaType.APPLICATION_JSON_VALUE)),
          new OperationContract(
              "/api/v1/runs/{runId}/artifacts/{artifactId}",
              "get",
              List.of("200", "404", "500"),
              Map.of("200", MediaType.IMAGE_PNG_VALUE)),
          new OperationContract(
              "/api/v1/tests",
              "get",
              List.of("200", "400", "503", "500"),
              Map.of("200", MediaType.APPLICATION_JSON_VALUE)));

  @Test
  void everyOperationDocumentsExactlyItsExpectedResponseCodesAndSuccessMediaTypes() {
    for (OperationContract contract : OPERATION_CONTRACTS) {
      assertThat(responseCodesFor(contract.path(), contract.method()))
          .as("%s %s response codes", contract.method(), contract.path())
          .containsExactlyInAnyOrderElementsOf(contract.expectedCodes());

      contract
          .successMediaTypes()
          .forEach(
              (code, expectedMediaType) -> {
                JsonNode content =
                    spec.path("paths")
                        .path(contract.path())
                        .path(contract.method())
                        .path("responses")
                        .path(code)
                        .path("content");
                assertThat(content.has(expectedMediaType))
                    .as(
                        "%s %s -> %s should declare %s",
                        contract.method(), contract.path(), code, expectedMediaType)
                    .isTrue();
                assertThat(content.has("*/*"))
                    .as(
                        "%s %s -> %s should not fall back to a wildcard media type",
                        contract.method(), contract.path(), code)
                    .isFalse();
              });
    }
  }

  /**
   * Every 4xx/5xx response found anywhere in the spec must be {@code application/problem+json}
   * against the {@code ProblemDetail} schema. Complements {@link
   * #everyOperationDocumentsExactlyItsExpectedResponseCodesAndSuccessMediaTypes}, which only
   * catches a response disappearing, not one drifting to the wrong content type or schema.
   */
  @Test
  void everyDocumentedErrorResponseUsesProblemJsonAndTheProblemDetailSchema() {
    List<String> violations = new ArrayList<>();
    Iterator<Map.Entry<String, JsonNode>> paths = spec.path("paths").fields();
    while (paths.hasNext()) {
      Map.Entry<String, JsonNode> pathEntry = paths.next();
      Iterator<Map.Entry<String, JsonNode>> methods = pathEntry.getValue().fields();
      while (methods.hasNext()) {
        Map.Entry<String, JsonNode> methodEntry = methods.next();
        Iterator<Map.Entry<String, JsonNode>> responses =
            methodEntry.getValue().path("responses").fields();
        while (responses.hasNext()) {
          Map.Entry<String, JsonNode> responseEntry = responses.next();
          if (!isErrorStatusCode(responseEntry.getKey())) {
            continue;
          }
          JsonNode response = responseEntry.getValue();
          boolean hasProblemJson = response.path("content").has("application/problem+json");
          String schemaRef =
              response
                  .path("content")
                  .path("application/problem+json")
                  .path("schema")
                  .path("$ref")
                  .asText();
          if (!hasProblemJson || !"#/components/schemas/ProblemDetail".equals(schemaRef)) {
            List<String> contentTypes = new ArrayList<>();
            response.path("content").fieldNames().forEachRemaining(contentTypes::add);
            violations.add(
                methodEntry.getKey()
                    + " "
                    + pathEntry.getKey()
                    + " -> "
                    + responseEntry.getKey()
                    + " (content types: "
                    + contentTypes
                    + ")");
          }
        }
      }
    }
    assertThat(violations)
        .as("every 4xx/5xx response must be application/problem+json with the ProblemDetail schema")
        .isEmpty();
  }

  private static boolean isErrorStatusCode(String code) {
    if (code.length() != 3) {
      return false;
    }
    try {
      int value = Integer.parseInt(code);
      return value >= 400 && value < 600;
    } catch (NumberFormatException notNumeric) {
      return false;
    }
  }

  @Test
  void sseEndpointIsExcludedFromTheGeneratedDocument() {
    assertThat(spec.path("paths").has("/api/v1/runs/{runId}/events")).isFalse();
  }

  @Test
  void runResponseHasExactlyTheExpectedRequiredFields() {
    JsonNode runResponse = spec.path("components").path("schemas").path("RunResponse");
    assertThat(requiredFieldsOf(runResponse))
        .containsExactlyInAnyOrder(
            "runId",
            "environment",
            "suite",
            "status",
            "requestedAt",
            "processLogUrl",
            "selectedTests",
            "artifactsPurged");

    JsonNode properties = runResponse.path("properties");
    for (String terminalField : List.of("startedAt", "finishedAt", "exitCode", "detail")) {
      assertThat(properties.has(terminalField))
          .as("%s should still be documented, just not required", terminalField)
          .isTrue();
    }
  }

  @Test
  void capabilitiesResponseHasExactlyTheExpectedRequiredFields() {
    JsonNode capabilitiesResponse =
        spec.path("components").path("schemas").path("CapabilitiesResponse");
    assertThat(requiredFieldsOf(capabilitiesResponse))
        .containsExactlyInAnyOrder("apiVersion", "eventSchemaVersion", "environments");
  }

  @Test
  void environmentCapabilitiesExposesNameAndSuitesAsRequired() {
    JsonNode environmentCapabilities =
        spec.path("components").path("schemas").path("EnvironmentCapabilities");
    assertThat(environmentCapabilities.path("properties").has("name")).isTrue();
    assertThat(environmentCapabilities.path("properties").has("suites")).isTrue();
    assertThat(requiredFieldsOf(environmentCapabilities))
        .containsExactlyInAnyOrder("name", "suites");
  }

  @Test
  void createRunRequestHasExactlyTheExpectedRequiredFields() {
    JsonNode createRunRequest = spec.path("components").path("schemas").path("CreateRunRequest");
    assertThat(requiredFieldsOf(createRunRequest))
        .containsExactlyInAnyOrder("environment", "suite");
  }

  /**
   * Locks the invariants {@code OpenApiConfig#problemDetailContractCustomizer} enforces:
   * title/status/detail/instance are always required ({@code type}/{@code properties} deliberately
   * are not). {@code instance} must not carry {@code format: uri} - typed-openapi maps that to
   * Zod's {@code z.url()}, which rejects the relative path Spring actually sends.
   */
  @Test
  void problemDetailHasExactlyTheExpectedRequiredFields() {
    JsonNode problemDetail = spec.path("components").path("schemas").path("ProblemDetail");
    assertThat(requiredFieldsOf(problemDetail))
        .containsExactlyInAnyOrder("title", "status", "detail", "instance");
  }

  @Test
  void problemDetailInstanceIsAPlainStringNotAnAbsoluteUri() {
    JsonNode instance =
        spec.path("components")
            .path("schemas")
            .path("ProblemDetail")
            .path("properties")
            .path("instance");
    assertThat(instance.path("type").asText()).isEqualTo("string");
    assertThat(instance.has("format"))
        .as("instance must not carry format: uri - real values are relative URI references")
        .isFalse();
  }

  /**
   * This endpoint's {@code text/plain} content is UTF-8 log text meant to be read, not opaque
   * bytes, so the schema must stay a plain string. {@code format: binary} would map to Zod's {@code
   * z.custom<Blob>(...)} while the generated client parses {@code text/plain} as a string via
   * {@code response.text()} - that mismatch would break output validation on every real log
   * download.
   */
  @Test
  void downloadRunLogSchemaIsAPlainStringNotBinary() {
    JsonNode schema =
        spec.path("paths")
            .path("/api/v1/runs/{runId}/log")
            .path("get")
            .path("responses")
            .path("200")
            .path("content")
            .path(MediaType.TEXT_PLAIN_VALUE)
            .path("schema");
    assertThat(schema.path("type").asText()).isEqualTo("string");
    assertThat(schema.path("format").asText()).isNotEqualTo("binary");
  }

  /**
   * Complements {@link
   * #everyOperationDocumentsExactlyItsExpectedResponseCodesAndSuccessMediaTypes}, which only
   * spot-checks one media type per operation ({@code image/png} here) - locks that all three {@link
   * dev.vlaisanem.automation.runner.contract.ArtifactType} media types are documented.
   */
  @Test
  void downloadRunArtifactDocumentsEveryArtifactTypesMediaType() {
    JsonNode content =
        spec.path("paths")
            .path("/api/v1/runs/{runId}/artifacts/{artifactId}")
            .path("get")
            .path("responses")
            .path("200")
            .path("content");
    assertThat(content.has(MediaType.IMAGE_PNG_VALUE)).isTrue();
    assertThat(content.has("application/zip")).isTrue();
    assertThat(content.has("video/webm")).isTrue();
  }

  @Test
  void artifactSummaryResponseHasExactlyTheExpectedRequiredFields() {
    JsonNode artifactSummaryResponse =
        spec.path("components").path("schemas").path("ArtifactSummaryResponse");
    assertThat(requiredFieldsOf(artifactSummaryResponse))
        .containsExactlyInAnyOrder(
            "artifactId",
            "testId",
            "testDisplayName",
            "type",
            "mediaType",
            "sizeBytes",
            "createdAt",
            "downloadUrl");
  }

  private List<String> allOperationIds() {
    List<String> ids = new ArrayList<>();
    Iterator<Map.Entry<String, JsonNode>> paths = spec.path("paths").fields();
    while (paths.hasNext()) {
      Iterator<Map.Entry<String, JsonNode>> methods = paths.next().getValue().fields();
      while (methods.hasNext()) {
        ids.add(methods.next().getValue().path("operationId").asText());
      }
    }
    return ids;
  }

  private List<String> responseCodesFor(String path, String method) {
    List<String> codes = new ArrayList<>();
    spec.path("paths")
        .path(path)
        .path(method)
        .path("responses")
        .fieldNames()
        .forEachRemaining(codes::add);
    return codes;
  }

  private List<String> requiredFieldsOf(JsonNode schema) {
    List<String> required = new ArrayList<>();
    schema.path("required").forEach(node -> required.add(node.asText()));
    return required;
  }
}
