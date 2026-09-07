package dev.vlaisanem.automation.runner;

import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.config.RunnerSecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * D2.3 - {@code DataSourceAutoConfiguration}/{@code FlywayAutoConfiguration} are no longer
 * excluded: {@code RunLifecycleStore}'s one production implementation ({@code JdbcRunStore}) is now
 * a real {@code @Component}, so every startup needs a real, reachable Postgres to construct it and
 * to run Flyway's migration against - {@code application.yml}'s own {@code spring.datasource.*}
 * defaults point local {@code bootRun} at {@code localhost:5433} (see {@code localPostgresUp} in
 * {@code runner-service/build.gradle}), and {@code deploy/docker-compose.yml} overrides every one
 * of those keys via {@code SPRING_DATASOURCE_*} env vars to point at its sibling {@code postgres}
 * service instead. The two Docker-free, real-Postgres-free full-{@code @SpringBootTest}-context
 * tests that predate this ({@code OpenApiContractTest}/{@code ServerBindingTest}) explicitly
 * re-exclude both autoconfigurations at the test level (via {@code spring.autoconfigure.exclude})
 * and mock {@code RunLifecycleStore} - see their own class Javadoc.
 */
@SpringBootApplication
@EnableConfigurationProperties({RunnerProperties.class, RunnerSecurityProperties.class})
public class RunnerServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(RunnerServiceApplication.class, args);
  }
}
