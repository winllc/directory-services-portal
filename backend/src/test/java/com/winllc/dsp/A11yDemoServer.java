package com.winllc.dsp;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import org.springframework.boot.SpringApplication;

/**
 * The portal as the accessibility checks (web/e2e, CI job "accessibility") see it: the embedded
 * demo directory and configuration, with a full license signed by the published ENS TEST key (as
 * the tests use; a production build never trusts it). Test code only, never packaged:
 *
 * <pre>./mvnw spring-boot:test-run -Dspring-boot.run.main-class=com.winllc.dsp.A11yDemoServer</pre>
 */
public final class A11yDemoServer {
  private A11yDemoServer() {}

  public static void main(String[] args) throws IOException {
    TestLicensing.use(TestLicenses.full());
    SpringApplication app = new SpringApplication(PortalApplication.class, TestLicensing.Config.class);
    app.setDefaultProperties(Map.of(
        "portal.directory-mode", "memory",
        "portal.seed-demo-config", "true",
        "portal.data-dir", Files.createTempDirectory("dsp-a11y").toString(),
        "server.port", System.getProperty("server.port", "3001")));
    app.run(args);
  }
}
