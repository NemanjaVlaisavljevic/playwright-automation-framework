package dev.vlaisanem.automation.support;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class SchemaValidator {
  private static final SchemaRegistry REGISTRY =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  private SchemaValidator() {}

  public static List<String> validate(String schemaResource, String json) {
    Schema schema = REGISTRY.getSchema(readResource(schemaResource), InputFormat.JSON);
    return schema.validate(json, InputFormat.JSON).stream().map(Object::toString).toList();
  }

  private static String readResource(String name) {
    try (InputStream input =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(name)) {
      if (input == null) {
        throw new IllegalArgumentException("Schema resource not found: " + name);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalArgumentException("Could not read schema resource: " + name, exception);
    }
  }
}
