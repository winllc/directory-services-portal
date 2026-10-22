package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The generated OpenAPI document of the integration API: written to target/openapi (CI publishes
 * it) and compared with the copy kept in the repository (openapi/dsp-api-v1.json), so a change to
 * the API can't go unnoticed. After an intended change, run the tests with -Dopenapi.update=true
 * and commit the new copy.
 */
final class OpenApiDocument {
  static final Path GENERATED = Path.of("target", "openapi", "dsp-api-v1.json");
  static final Path KEPT = Path.of("openapi", "dsp-api-v1.json");

  private OpenApiDocument() {}

  static void write(String json) throws IOException {
    JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    JsonNode doc = mapper.readTree(json);
    String pretty = mapper.writeValueAsString(doc) + "\n";
    Files.createDirectories(GENERATED.getParent());
    Files.writeString(GENERATED, pretty, StandardCharsets.UTF_8);
    if (Boolean.getBoolean("openapi.update")) {
      Files.createDirectories(KEPT.getParent());
      Files.writeString(KEPT, pretty, StandardCharsets.UTF_8);
      return;
    }
    assertThat(Files.exists(KEPT)).as("%s is missing: run the tests with -Dopenapi.update=true", KEPT).isTrue();
    assertThat(mapper.readTree(Files.readString(KEPT, StandardCharsets.UTF_8)))
        .as("The integration API changed: run the tests with -Dopenapi.update=true and commit %s", KEPT)
        .isEqualTo(doc);
  }
}
