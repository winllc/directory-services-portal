package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** A deployment whose license enables metrics only: analysis and schema import/export are refused. */
class LicenseGatingTest extends ApiTestSupport {

  @DynamicPropertySource
  static void license(DynamicPropertyRegistry registry) throws IOException {
    Path file = Files.createTempFile("dsp-license", ".lic");
    Files.writeString(file, TestLicenses.sign("metrics", "never"));
    registry.add("test.license-file", file::toString);
  }

  @Test
  void refusesUnlicensedFeaturesWithJson() throws Exception {
    Client admin = login("admin");
    for (String url : new String[] {"/api/admin/analysis", "/api/admin/analysis/x/findings.csv", "/api/admin/schema/export"}) {
      MvcResult r = admin.get(url);
      assertThat(status(r)).as(url).isEqualTo(403);
      JsonNode b = body(r);
      assertThat(b.get("code").asString()).isEqualTo("license_required");
      assertThat(b.get("error").asString()).endsWith("requires an ENS Solutions license.");
    }
    MvcResult r = admin.post("/api/admin/schema/import", Map.of("text", ""));
    assertThat(status(r)).isEqualTo(403);
    assertThat(body(r).get("feature").asString()).isEqualTo("schema-transfer");
    assertThat(body(r).get("error").asString()).isEqualTo("Schema import and export requires an ENS Solutions license.");
  }

  @Test
  void keepsCoreAndLicensedFeaturesWorking() throws Exception {
    Client admin = login("admin");
    assertThat(status(admin.get("/api/admin/definitions"))).isEqualTo(200);
    assertThat(status(admin.get("/api/admin/schema"))).isEqualTo(200);
    assertThat(status(admin.get("/api/metrics/sources"))).isEqualTo(200);
    assertThat(status(admin.get("/api/health"))).isEqualTo(200);
  }

  @Test
  void stillRequiresSignInAndAdminFirst() throws Exception {
    assertThat(status(anonymous(anonGet("/api/admin/analysis")))).isEqualTo(401);
    assertThat(status(login("alice").get("/api/admin/analysis"))).isEqualTo(403);
    assertThat(body(login("alice").get("/api/admin/analysis")).get("error").asString()).isEqualTo("Administrator access required");
  }

  @Test
  void reportsWhichFeaturesTheUiShouldShow() throws Exception {
    JsonNode admin = body(login("admin").get("/api/license"));
    assertThat(admin.get("state").asString()).isEqualTo("LICENSED");
    assertThat(admin.get("licensee").asString()).isEqualTo("Test Suite");
    assertThat(admin.has("file")).isTrue();
    Map<String, Boolean> enabled = new HashMap<>();
    admin.get("features").forEach(f -> enabled.put(f.get("id").asString(), f.get("enabled").asBoolean()));
    assertThat(enabled).containsExactlyInAnyOrderEntriesOf(Map.of("analysis", false, "metrics", true, "schema-transfer", false));

    JsonNode user = body(login("alice").get("/api/license"));
    assertThat(user.has("file")).isFalse();
    assertThat(status(anonymous(anonGet("/api/license")))).isEqualTo(401);
  }
}
