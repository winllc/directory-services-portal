package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.winllc.dsp.audit.AuditLog;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Health probes, the trimmed anonymous /api/info and the ens_* metrics (ENS platform baseline).
 * Without MANAGEMENT_SERVER_PORT, Prometheus is not exposed (see {@link ManagementPortTest}).
 */
class ObservabilityTest extends ApiTestSupport {
  @Autowired MeterRegistry meters;
  @Autowired AuditLog audit;

  private double count(String name, String... tags) {
    var counter = meters.find(name).tags(tags).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void answersTheProbesWithoutSigningIn() throws Exception {
    for (String path : new String[] {"/actuator/health/liveness", "/actuator/health/readiness", "/livez", "/readyz", "/api/health"}) {
      MvcResult r = anonymous(get(path));
      assertThat(status(r)).as(path).isEqualTo(200);
    }
    JsonNode readiness = body(anonymous(get("/actuator/health/readiness")));
    assertThat(readiness.get("status").asString()).isEqualTo("UP");
    // Details only for administrators.
    assertThat(readiness.has("components")).isFalse();
    JsonNode detailed = body(login("admin").get("/actuator/health/readiness"));
    assertThat(detailed.get("components").propertyNames()).contains("ldap", "auditLog", "diskSpace", "readinessState");
    assertThat(body(login("admin").get("/actuator/health/liveness")).get("components").propertyNames()).containsExactly("livenessState");
    assertThat(body(login("alice").get("/actuator/health/readiness")).has("components")).isFalse();
  }

  @Test
  void answersTheProbesWithoutALicense() throws Exception {
    TestLicensing.remove();
    assertThat(status(anonymous(get("/actuator/health/readiness")))).isEqualTo(200);
    assertThat(status(anonymous(get("/readyz")))).isEqualTo(200);
  }

  @Test
  void isNotReadyWhileTheAuditLogCantBeWritten() throws Exception {
    Path store = audit.store().dir();
    Path aside = store.resolveSibling(store.getFileName() + ".aside");
    Files.move(store, aside);
    Files.writeString(store, "not a directory");
    MvcResult readiness;
    MvcResult liveness;
    try {
      readiness = anonymous(get("/actuator/health/readiness"));
      liveness = anonymous(get("/actuator/health/liveness"));
    } finally {
      Files.delete(store);
      Files.move(aside, store);
    }
    assertThat(status(readiness)).isEqualTo(503);
    assertThat(status(liveness)).isEqualTo(200);
  }

  @Test
  void exposesNoPrometheusEndpointWithoutAManagementPort() throws Exception {
    assertThat(status(anonymous(get("/actuator/prometheus")))).isEqualTo(404);
    assertThat(status(login("admin").get("/actuator/prometheus"))).isEqualTo(404);
  }

  @Test
  void tellsAnonymousCallersNothingAboutTheDirectoryButWhetherItAnswers() throws Exception {
    JsonNode anonymousInfo = body(anonymous(get("/api/info")));
    assertThat(anonymousInfo.get("connected").asBoolean()).isTrue();
    assertThat(anonymousInfo.has("baseDn")).isFalse();
    assertThat(anonymousInfo.has("ldapUrl")).isFalse();
    assertThat(anonymousInfo.has("error")).isFalse();
    assertThat(anonymousInfo.get("auth").get("password").asBoolean()).isTrue();

    JsonNode userInfo = body(login("alice").get("/api/info"));
    assertThat(userInfo.get("baseDn").asString()).isEqualTo(B);
    assertThat(userInfo.has("ldapUrl")).isFalse();
  }

  @Test
  void countsSignInsAuditEventsEntryWritesAndLdapOperations() throws Exception {
    double logins = count("ens.auth.attempts", "method", "password", "outcome", "success");
    double failures = count("ens.auth.attempts", "method", "password", "outcome", "failure");
    double audited = count("ens.audit.events", "category", "authentication", "outcome", "success");
    double updates = count("ens.dsp.entry.writes", "operation", "update", "outcome", "success");
    double denied = count("ens.dsp.entry.writes", "operation", "update", "outcome", "denied");

    Client erin = login("erin");
    anonymous(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/login"),
        Map.of("username", "erin", "password", "wrong")));
    assertThat(status(erin.put("/api/directories/def-staff/entry" + q(ALICE), Map.of("values", Map.of("title", "Counted"))))).isEqualTo(200);
    assertThat(status(login("alice").put("/api/directories/def-staff/entry" + q(ALICE), Map.of("values", Map.of("title", "No"))))).isEqualTo(403);

    assertThat(count("ens.auth.attempts", "method", "password", "outcome", "success")).isEqualTo(logins + 2);
    assertThat(count("ens.auth.attempts", "method", "password", "outcome", "failure")).isEqualTo(failures + 1);
    assertThat(count("ens.audit.events", "category", "authentication", "outcome", "success")).isGreaterThanOrEqualTo(audited + 2);
    assertThat(count("ens.dsp.entry.writes", "operation", "update", "outcome", "success")).isEqualTo(updates + 1);
    assertThat(count("ens.dsp.entry.writes", "operation", "update", "outcome", "denied")).isEqualTo(denied + 1);

    assertThat(meters.find("ens.dsp.ldap.operation").tags("operation", "modify", "outcome", "success").timer().count()).isPositive();
    assertThat(meters.find("ens.dsp.ldap.operation").tags("operation", "bind", "outcome", "success").timer().count()).isPositive();
    assertThat(meters.find("ens.dsp.ldap.pool.available").gauge().value()).isGreaterThanOrEqualTo(0);
    assertThat(meters.find("ens.license.mode").tags("mode", "full").gauge().value()).isEqualTo(1);
    assertThat(meters.find("ens.license.mode").tags("mode", "unlicensed").gauge().value()).isZero();
    assertThat(meters.find("ens.license.days.remaining").gauge()).isNotNull();
  }
}
