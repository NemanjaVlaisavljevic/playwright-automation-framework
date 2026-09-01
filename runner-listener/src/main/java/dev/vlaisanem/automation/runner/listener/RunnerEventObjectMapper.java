package dev.vlaisanem.automation.runner.listener;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Creates the JSON mapper used to write runner contract events as JSON Lines. */
public final class RunnerEventObjectMapper {

  private RunnerEventObjectMapper() {}

  public static ObjectMapper create() {
    return new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
  }
}
