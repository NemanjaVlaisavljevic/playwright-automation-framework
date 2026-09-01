package dev.vlaisanem.automation.runner.service.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the classic Jackson 2 {@link ObjectMapper} that {@code FileBackedRunEventJournal} and
 * {@code ListenerEventIngestor} are written against. Spring Boot 4's own {@code
 * spring-boot-starter-json} autoconfigures a Jackson <em>3</em> ({@code tools.jackson.databind})
 * {@code ObjectMapper} instead - a completely different type from a different artifact - so without
 * this bean, the context fails to start the moment anything asks Spring for a {@code
 * com.fasterxml.jackson.databind.ObjectMapper}. Configured to match runner-listener's own {@code
 * RunnerEventObjectMapper}, since both sides serialize the same {@code RunnerEvent} contract type
 * and must agree on the wire format.
 */
@Configuration
public class JacksonConfig {

  @Bean
  public ObjectMapper objectMapper() {
    return new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
  }
}
