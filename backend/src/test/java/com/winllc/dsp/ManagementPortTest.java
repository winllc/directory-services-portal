package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * With MANAGEMENT_SERVER_PORT set, health and Prometheus are served on that port without sign-in,
 * the port users reach keeps only /livez and /readyz, and the metrics carry the ens_* names.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import(TestLicensing.Config.class)
class ManagementPortTest {
  private static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

  @LocalServerPort int port;
  @LocalManagementPort int managementPort;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) throws IOException {
    String dir = Files.createTempDirectory("dsp-mgmt-test").toString();
    registry.add("portal.directory-mode", () -> "memory");
    registry.add("portal.data-dir", () -> dir);
  }

  @BeforeAll
  static void license() {
    TestLicensing.use(TestLicenses.full());
  }

  private static HttpResponse<String> get(int port, String path) throws Exception {
    return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void servesHealthAndPrometheusOnlyOnTheManagementPort() throws Exception {
    assertThat(managementPort).isNotEqualTo(port);
    assertThat(get(managementPort, "/actuator/health/liveness").statusCode()).isEqualTo(200);
    assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
    HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus");
    assertThat(scrape.statusCode()).isEqualTo(200);
    assertThat(scrape.body()).contains("ens_license_mode{", "ens_license_days_remaining", "ens_dsp_ldap_pool_available",
        "ens_dsp_ldap_operation_seconds_count{", "ens_audit_events_total{");

    assertThat(get(port, "/actuator/prometheus").statusCode()).isEqualTo(404);
    assertThat(get(port, "/actuator/health").statusCode()).isEqualTo(404);
    assertThat(get(port, "/livez").statusCode()).isEqualTo(200);
    assertThat(get(port, "/readyz").statusCode()).isEqualTo(200);
    // The API's own security headers reach clients that call it directly.
    assertThat(get(port, "/api/info").headers().firstValue("Content-Security-Policy")).isPresent();
  }
}
