package dev.vlaisanem.automation.runner.service.catalog;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vlaisanem.automation.runner.service.domain.TestLayer;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = TestCatalogController.class)
class TestCatalogControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private TestCatalogService testCatalogService;

  @Test
  void listReturnsTheCatalogForPublic() throws Exception {
    given(testCatalogService.current())
        .willReturn(
            List.of(
                new TestCatalogEntry(
                    "some.Test#method", "Some test", TestLayer.API, Set.of("regression"))));

    mockMvc
        .perform(get("/api/v1/tests").param("environment", "PUBLIC"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tests[0].testKey").value("some.Test#method"))
        .andExpect(jsonPath("$.tests[0].category").value("API"));
  }

  @Test
  void listRejectsLocalWithoutEverCallingTheCatalog() throws Exception {
    mockMvc
        .perform(get("/api/v1/tests").param("environment", "LOCAL"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.detail").value("CUSTOM test selection only exists for PUBLIC, got LOCAL"));

    verify(testCatalogService, never()).current();
  }

  @Test
  void listReturns400WhenEnvironmentIsMissing() throws Exception {
    mockMvc.perform(get("/api/v1/tests")).andExpect(status().isBadRequest());
  }

  @Test
  void listReturns503WhenTheCatalogFileIsUnavailable() throws Exception {
    given(testCatalogService.current())
        .willThrow(new TestCatalogUnavailableException(Path.of("catalog.json")));

    mockMvc
        .perform(get("/api/v1/tests").param("environment", "PUBLIC"))
        .andExpect(status().isServiceUnavailable());
  }
}
