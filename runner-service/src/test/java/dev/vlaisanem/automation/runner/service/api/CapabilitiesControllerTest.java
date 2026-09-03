package dev.vlaisanem.automation.runner.service.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = CapabilitiesController.class)
class CapabilitiesControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void getReturnsTheAllowlistedEnvironmentsAndSuites() throws Exception {
    mockMvc
        .perform(get("/api/v1/capabilities"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.apiVersion").value("v1"))
        .andExpect(jsonPath("$.eventSchemaVersion").value(RunnerEvent.CURRENT_SCHEMA_VERSION))
        .andExpect(jsonPath("$.environments[0].name").value("PUBLIC"))
        .andExpect(jsonPath("$.environments[0].suites[0]").value("SMOKE"))
        .andExpect(jsonPath("$.environments[0].suites[4]").value("REGRESSION"))
        .andExpect(jsonPath("$.environments[0].suites[5]").value("FIXTURE"))
        .andExpect(jsonPath("$.environments[1].name").value("LOCAL"))
        .andExpect(jsonPath("$.environments[1].suites[0]").value("JOURNEY"))
        .andExpect(jsonPath("$.environments[1].suites.length()").value(1));
  }
}
