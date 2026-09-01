package dev.vlaisanem.automation.runner;

import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RunnerProperties.class)
public class RunnerServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(RunnerServiceApplication.class, args);
  }
}
