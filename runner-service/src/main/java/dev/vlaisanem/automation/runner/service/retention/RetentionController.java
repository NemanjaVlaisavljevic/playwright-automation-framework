package dev.vlaisanem.automation.runner.service.retention;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only operational tooling, not a dashboard feature, so {@link Hidden} from the public
 * OpenAPI document. Both routes require {@code ROLE_ADMIN}; {@code POST .../run} additionally
 * requires a valid CSRF token, same as {@code POST /api/v1/runs}/{@code .../cancel}.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/retention")
public class RetentionController {

  private final RetentionService retentionService;

  public RetentionController(RetentionService retentionService) {
    this.retentionService = retentionService;
  }

  /** Dry run - computes candidate counts only, no claim/delete/write of any kind. */
  @GetMapping("/preview")
  public RetentionReport preview() {
    return retentionService.sweep(true);
  }

  /** Runs a real sweep on demand, identical to what the scheduled background job runs. */
  @PostMapping("/run")
  public RetentionReport run() {
    return retentionService.sweep(false);
  }
}
