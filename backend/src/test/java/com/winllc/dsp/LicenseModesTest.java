package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** The ENS license's modes and features, as the API applies them. */
class LicenseModesTest extends ApiTestSupport {

  static final String STAFF = "/api/directories/def-staff/entries";

  MvcResult upload(Client c, String text) throws Exception {
    return c.send(post("/api/admin/license").header("X-DSP-Request", "1").contentType(MediaType.TEXT_PLAIN).content(text));
  }

  List<String> definitionIds(Client c) throws Exception {
    List<String> ids = new ArrayList<>();
    body(c.get("/api/definitions")).forEach(d -> ids.add(d.get("id").asString()));
    return ids;
  }

  @Nested
  class Unlicensed extends ApiTestSupport {
    @Test
    void onlySignInInfoHealthAndTheLicenseWork() throws Exception {
      TestLicensing.remove();
      assertThat(status(anonymous(anonGet("/api/health")))).isEqualTo(200);
      assertThat(status(anonymous(anonGet("/api/info")))).isEqualTo(200);
      Client admin = login("admin");
      for (String url : new String[] {"/api/definitions", STAFF, "/api/admin/definitions", "/api/admin/audit", "/api/jobs", "/api/metrics/sources"}) {
        MvcResult r = admin.get(url);
        assertThat(status(r)).as(url).isEqualTo(403);
        JsonNode b = body(r);
        assertThat(b.get("code").asString()).as(url).isEqualTo("license_required");
        assertThat(b.get("state").asString()).isEqualTo("MISSING");
        assertThat(b.get("error").asString()).isEqualTo("No license is installed. Upload one on the license screen.");
      }
      JsonNode status = body(admin.get("/api/license"));
      assertThat(status.get("mode").asString()).isEqualTo("UNLICENSED");
      assertThat(status.get("details").get("installationId").asString()).matches("DP-[0-9A-Z]{4}-[0-9A-Z]{4}");
      status.get("features").forEach(f -> assertThat(f.get("enabled").asBoolean()).isFalse());
      assertThat(status(admin.post("/api/auth/logout", null))).isEqualTo(200);
    }

    @Test
    void regularUsersCanSignInButSeeNoDetails() throws Exception {
      TestLicensing.remove();
      Client alice = login("alice");
      assertThat(body(alice.get("/api/definitions")).get("code").asString()).isEqualTo("license_required");
      JsonNode status = body(alice.get("/api/license"));
      assertThat(status.get("mode").asString()).isEqualTo("UNLICENSED");
      assertThat(status.has("details")).isFalse();
      // Uploading is for administrators.
      assertThat(status(upload(alice, TestLicenses.full()))).isEqualTo(403);
    }

    @Test
    void stillRequiresSignInFirst() throws Exception {
      TestLicensing.remove();
      assertThat(status(anonymous(anonGet("/api/definitions")))).isEqualTo(401);
      assertThat(status(anonymous(anonGet("/api/license")))).isEqualTo(401);
    }

    @Test
    void wrongProductOrInstallationOnDiskIsUnlicensed() throws Exception {
      TestLicensing.use(TestLicenses.license().product("certalert").features("metrics").sign());
      Client admin = login("admin");
      JsonNode b = body(admin.get("/api/definitions"));
      assertThat(b.get("state").asString()).isEqualTo("WRONG_PRODUCT");
      TestLicensing.use(TestLicenses.license().installation("DP-0THE-R000").sign());
      assertThat(body(admin.get("/api/definitions")).get("state").asString()).isEqualTo("WRONG_INSTALLATION");
      TestLicensing.use(TestLicenses.license().notYetValid().sign());
      assertThat(body(admin.get("/api/definitions")).get("state").asString()).isEqualTo("NOT_YET_VALID");
    }
  }

  @Nested
  class ReadOnly extends ApiTestSupport {
    @Test
    void readsWorkChangesAreRefused() throws Exception {
      TestLicensing.use(TestLicenses.license().expired().sign());
      Client admin = login("admin");
      assertThat(status(admin.get("/api/definitions"))).isEqualTo(200);
      assertThat(status(admin.get(STAFF))).isEqualTo(200);
      assertThat(status(admin.get("/api/metrics/sources"))).isEqualTo(200);
      MvcResult r = admin.put("/api/admin/forms/form-staff", Map.of());
      assertThat(status(r)).isEqualTo(403);
      assertThat(body(r).get("code").asString()).isEqualTo("license_read_only");
      assertThat(body(r).get("state").asString()).isEqualTo("EXPIRED");
      assertThat(body(r).get("error").asString()).contains("read-only");
      assertThat(body(admin.del("/api/directories/def-staff/entry" + q(ALICE))).get("code").asString()).isEqualTo("license_read_only");
      assertThat(body(admin.post("/api/admin/grants", Map.of())).get("code").asString()).isEqualTo("license_read_only");
      // Sign-out and the license upload still work.
      assertThat(status(upload(admin, TestLicenses.full()))).isEqualTo(200);
      assertThat(status(admin.get(STAFF))).isEqualTo(200);
      assertThat(status(admin.post("/api/auth/logout", null))).isEqualTo(200);
    }

    @Test
    void backgroundWorkPauses() throws Exception {
      // The portal has no scheduled licensed jobs; its background work (analysis runs, metric
      // evaluations) is started by requests that change or compute, which read-only refuses.
      TestLicensing.use(TestLicenses.license().expired().sign());
      Client admin = login("admin");
      assertThat(body(admin.post("/api/admin/analysis/an-people-quality/run", null)).get("code").asString()).isEqualTo("license_read_only");
      assertThat(body(admin.post("/api/metrics/evaluate", Map.of("widgets", List.of()))).get("code").asString()).isEqualTo("license_read_only");
      assertThat(body(admin.get("/api/jobs")).isArray()).isTrue();
    }

    @Test
    void featuresTheLicenseDoesNotListStayOff() throws Exception {
      TestLicensing.use(TestLicenses.license().expired().without("metrics").sign());
      Client admin = login("admin");
      assertThat(body(admin.get("/api/metrics/sources")).get("code").asString()).isEqualTo("feature_not_licensed");
      assertThat(status(admin.get(STAFF))).isEqualTo(200);
    }
  }

  @Nested
  class Features extends ApiTestSupport {
    @Test
    void refusesFeaturesTheLicenseLeavesOut() throws Exception {
      TestLicensing.use(TestLicenses.license().features("directory-editor", "white-pages").sign());
      Client admin = login("admin");
      Map<String, String> refused = Map.of(
          "/api/metrics/sources", "metrics",
          "/api/metrics/dashboards", "metrics",
          "/api/admin/analysis", "data-analysis",
          "/api/admin/analysis/x/findings.csv", "data-analysis",
          "/api/admin/schema/export", "schema-transfer",
          "/api/admin/forms", "form-builder",
          "/api/admin/definitions", "form-builder",
          "/api/admin/browse?base=dc%3Dexample%2Cdc%3Dcom", "form-builder",
          "/api/me/entries", "self-service-profile");
      for (var e : refused.entrySet()) {
        MvcResult r = admin.get(e.getKey());
        assertThat(status(r)).as(e.getKey()).isEqualTo(403);
        assertThat(body(r).get("code").asString()).as(e.getKey()).isEqualTo("feature_not_licensed");
        assertThat(body(r).get("feature").asString()).as(e.getKey()).isEqualTo(e.getValue());
      }
      assertThat(body(admin.post("/api/admin/schema/import", Map.of("text", ""))).get("error").asString())
          .isEqualTo("Schema import and export is not included in this portal's license.");
      // Core administration and the licensed directory editor keep working.
      assertThat(status(admin.get("/api/admin/schema"))).isEqualTo(200);
      assertThat(status(admin.get("/api/admin/grants"))).isEqualTo(200);
      assertThat(status(admin.get("/api/admin/audit"))).isEqualTo(200);
      assertThat(status(admin.get(STAFF))).isEqualTo(200);
    }

    @Test
    void reportsEveryCatalogFeatureForTheUi() throws Exception {
      TestLicensing.use(TestLicenses.license().features("directory-editor", "metrics").sign());
      JsonNode status = body(login("admin").get("/api/license"));
      assertThat(status.get("mode").asString()).isEqualTo("FULL");
      assertThat(status.get("state").asString()).isEqualTo("VALID");
      Map<String, Boolean> enabled = new HashMap<>();
      status.get("features").forEach(f -> enabled.put(f.get("id").asString(), f.get("enabled").asBoolean()));
      assertThat(enabled).containsExactlyInAnyOrderEntriesOf(Map.of(
          "directory-editor", true, "form-builder", false, "self-service-profile", false, "white-pages", false,
          "smart-card-sign-in", false, "metrics", true, "data-analysis", false, "schema-transfer", false));
      JsonNode d = status.get("details");
      assertThat(d.get("licensee").asString()).isEqualTo("Test Suite, ENS QA");
      assertThat(d.get("edition").asString()).isEqualTo("subscription");
      assertThat(d.get("size").get("quantity").asLong()).isEqualTo(2500);
      assertThat(d.get("size").get("unit").asString()).isEqualTo("directory users");
      assertThat(d.get("file").asString()).endsWith("license.lic");
      assertThat(status.get("evaluation").asBoolean()).isFalse();
      assertThat(status.has("renewalWarning")).isFalse();
    }

    @Test
    void selfServiceWorksWithoutTheDirectoryEditor() throws Exception {
      TestLicensing.use(TestLicenses.license().features("self-service-profile").sign());
      Client alice = login("alice");
      assertThat(status(alice.get("/api/me/entries"))).isEqualTo(200);
      assertThat(body(alice.get("/api/definitions")).get("feature").asString()).isEqualTo("directory-editor");
    }

    @Test
    void whitePagesIsReadAccessForEveryoneButAdministrators() throws Exception {
      Client alice = login("alice");
      assertThat(definitionIds(alice)).contains("def-staff", "def-whitepages", "def-devices");

      TestLicensing.use(TestLicenses.license().without("white-pages").sign());
      // alice only has read grants and everyone-can-read directories: nothing left.
      assertThat(definitionIds(alice)).isEmpty();
      assertThat(body(alice.get(STAFF)).get("error").asString()).isEqualTo("You do not have access to this directory");
      // Editors keep the directories they may change; administrators keep everything.
      assertThat(definitionIds(login("dave"))).containsExactly("def-devices");
      assertThat(definitionIds(login("admin"))).contains("def-staff", "def-whitepages", "def-devices", "def-groups", "def-partners");
      // Self-service doesn't depend on it.
      assertThat(status(alice.get("/api/me/entries"))).isEqualTo(200);
    }

    @Test
    void evaluationBannerAndRenewalWarning() throws Exception {
      TestLicensing.use(TestLicenses.license().evaluation().sign());
      JsonNode status = body(login("alice").get("/api/license"));
      assertThat(status.get("evaluation").asBoolean()).isTrue();

      TestLicensing.use(TestLicenses.license().days(10).sign());
      assertThat(body(login("alice").get("/api/license")).has("renewalWarning")).isFalse();
      JsonNode admin = body(login("admin").get("/api/license"));
      assertThat(admin.get("renewalWarning").asBoolean()).isTrue();
      assertThat(admin.get("daysLeft").asLong()).isBetween(9L, 10L);

      TestLicensing.use(TestLicenses.license().days(-5).sign());
      admin = body(login("admin").get("/api/license"));
      assertThat(admin.get("state").asString()).isEqualTo("GRACE");
      assertThat(admin.get("mode").asString()).isEqualTo("FULL");
      assertThat(admin.get("renewalWarning").asBoolean()).isTrue();
    }
  }

  @Nested
  class Upload extends ApiTestSupport {
    @Test
    void installsAValidLicense() throws Exception {
      TestLicensing.remove();
      Client admin = login("admin");
      MvcResult r = upload(admin, TestLicenses.license().features("metrics").sign());
      assertThat(status(r)).isEqualTo(200);
      assertThat(body(r).get("installed").asBoolean()).isTrue();
      assertThat(body(r).get("message").asString()).startsWith("Licensed to Test Suite, ENS QA until ");
      assertThat(body(r).get("license").get("mode").asString()).isEqualTo("FULL");
      assertThat(status(admin.get("/api/metrics/sources"))).isEqualTo(200);
      assertThat(java.nio.file.Files.readString(TestLicensing.FILE)).contains("BEGIN ENS LICENSE");
    }

    @Test
    void acceptsAMultipartFile() throws Exception {
      TestLicensing.remove();
      Client admin = login("admin");
      MvcResult r = mvc.perform(multipart("/api/admin/license")
          .file("file", TestLicenses.full().getBytes(StandardCharsets.UTF_8)).header("X-DSP-Request", "1").session(admin.session)).andReturn();
      assertThat(status(r)).isEqualTo(200);
      assertThat(status(admin.get(STAFF))).isEqualTo(200);
    }

    @Test
    void refusesAnotherProductsLicenseAndKeepsTheInstalledOne() throws Exception {
      Client admin = login("admin");
      MvcResult r = upload(admin, TestLicenses.license().product("certalert").features("metrics").sign());
      assertThat(status(r)).isEqualTo(400);
      assertThat(body(r).get("code").asString()).isEqualTo("license_refused");
      assertThat(body(r).get("state").asString()).isEqualTo("WRONG_PRODUCT");
      assertThat(body(r).get("error").asString()).isEqualTo("This is a license for certalert, not directory-portal.");
      assertThat(status(admin.get(STAFF))).isEqualTo(200);
    }

    @Test
    void bindsToThisInstallationOnly() throws Exception {
      Client admin = login("admin");
      String here = body(admin.get("/api/license")).get("details").get("installationId").asString();
      MvcResult elsewhere = upload(admin, TestLicenses.license().installation("DP-0THE-R000").sign());
      assertThat(status(elsewhere)).isEqualTo(400);
      assertThat(body(elsewhere).get("state").asString()).isEqualTo("WRONG_INSTALLATION");
      assertThat(body(elsewhere).get("error").asString()).contains("bound to installation DP-0THE-R000; this one is " + here);

      MvcResult bound = upload(admin, TestLicenses.license().installation(here).sign());
      assertThat(status(bound)).isEqualTo(200);
      assertThat(body(admin.get("/api/license")).get("details").get("boundTo").asString()).isEqualTo(here);
    }

    @Test
    void refusesFilesThatAreNotGenuineAndOlderRevisions() throws Exception {
      Client admin = login("admin");
      MvcResult junk = upload(admin, "hello");
      assertThat(status(junk)).isEqualTo(400);
      assertThat(body(junk).get("error").asString()).isEqualTo("That isn't a valid ENS license: Not an ENS license.");

      String good = TestLicenses.full();
      String tampered = good.replace(good.substring(good.indexOf('.') + 5, good.indexOf('.') + 6),
          good.charAt(good.indexOf('.') + 5) == 'A' ? "B" : "A");
      assertThat(body(upload(admin, tampered)).get("error").asString()).startsWith("That isn't a valid ENS license:");

      assertThat(status(upload(admin, TestLicenses.license().id("lic_revisions").revision(2).sign()))).isEqualTo(200);
      MvcResult older = upload(admin, TestLicenses.license().id("lic_revisions").revision(1).sign());
      assertThat(status(older)).isEqualTo(400);
      assertThat(body(older).get("state").asString()).isEqualTo("SUPERSEDED");
    }

    @Test
    void installsAnExpiredLicenseAndSaysSo() throws Exception {
      MvcResult r = upload(login("admin"), TestLicenses.license().expired().sign());
      assertThat(status(r)).isEqualTo(200);
      assertThat(body(r).get("license").get("mode").asString()).isEqualTo("READ_ONLY");
      assertThat(body(r).get("message").asString()).contains("read-only");
    }
  }
}
