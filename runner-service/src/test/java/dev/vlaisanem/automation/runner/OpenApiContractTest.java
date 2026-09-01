package dev.vlaisanem.automation.runner;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Locks the generated {@code /v3/api-docs} document against everything the frontend's typed-client
 * generator (Faza 2) will depend on - not a one-time manual read of the JSON, but a real HTTP call
 * against the running application, so a later backend refactor that silently breaks the frontend
 * contract fails here in CI rather than only being noticed once client generation itself breaks.
 *
 * <p>Each assertion here corresponds to a concrete finding from the springdoc spike: an unannotated
 * {@code POST /runs} defaulted to a spurious {@code 200} alongside the real {@code 202}; two
 * controllers both named {@code get()} produced colliding {@code get}/{@code get_1} operation IDs;
 * the SSE endpoint's generated schema described {@code SseEmitter}'s own {@code timeout} field
 * instead of the actual event payload; and {@code RunResponse} had no {@code required} array at
 * all, since springdoc infers nothing without an explicit {@code @Schema} or Bean Validation
 * annotation on a plain response record.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class OpenApiContractTest {

  private static final List<String> EXPECTED_OPERATION_IDS =
      List.of(
          "createRun",
          "listRuns",
          "getRun",
          "cancelRun",
          "downloadRunLog",
          "getRunnerCapabilities");

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
   * One record per operation, doubling as the exact-response-code matrix and the success media-type
   * expectations - so removing an {@code @ApiResponse} (e.g. {@code cancelRun}'s 503) fails this
   * test directly via the missing code, instead of just quietly narrowing what {@link
   * #everyDocumentedErrorResponseUsesProblemJsonAndTheProblemDetailSchema} happens to see.
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
   * Walks every documented response across every operation - not a hand-maintained partial list
   * that silently stops covering an endpoint's error responses the moment someone adds a new one -
   * so the test name actually describes what it checks. A 4xx/5xx response code found anywhere in
   * the spec must be {@code application/problem+json} against the {@code ProblemDetail} schema.
   * Complements {@link #everyOperationDocumentsExactlyItsExpectedResponseCodesAndSuccessMediaTypes}
   * (which catches a response disappearing entirely) by catching one that stays present but drifts
   * to the wrong content type or schema.
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
            "runId", "environment", "suite", "status", "requestedAt", "processLogUrl");

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
   * Locks both invariants {@code OpenApiConfig#problemDetailContractCustomizer} exists to fix -
   * verified empirically against a real {@code 404} response body ({@code
   * {"detail":"...","instance":"/api/v1/runs/does-not-exist","status":404,"title":"Not Found"}}):
   * {@code title}/{@code status}/{@code detail}/{@code instance} are always present ({@code type}
   * and {@code properties} deliberately are not, since that same response omits both); and {@code
   * instance} must not carry {@code format: uri}, since typed-openapi maps that to Zod's {@code
   * z.url()}, which rejects the relative path Spring actually sends.
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
   * Regression test for a real bug: the previous {@code @Schema(type = "string", format =
   * "binary")} annotation described an opaque binary payload, but this endpoint's actual {@code
   * text/plain} content is UTF-8 log text meant to be read, not opaque bytes. typed-openapi mapped
   * {@code format: binary} to Zod's {@code z.custom<Blob>(...)}, while the generated client's own
   * {@code text/plain} handling parses the body as a string via {@code response.text()} - output
   * validation against that mismatch would throw for every real log download. This test would have
   * failed against the original annotation; it must keep failing if {@code format: binary} ever
   * comes back.
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
