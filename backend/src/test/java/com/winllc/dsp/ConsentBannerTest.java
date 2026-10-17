package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.winllc.dsp.config.EnsProperties;
import com.winllc.dsp.config.PortalProperties;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * The DoD notice-and-consent banner (ENS_CONSENT_BANNER_*): enforced by the API, not only shown by
 * the client. No sign-in of any kind succeeds in a browser session that has not acknowledged it.
 */
class ConsentBannerTest extends ApiTestSupport {
  @Autowired EnsProperties ens;
  @Autowired PortalProperties props;
  private String since;

  @BeforeEach
  void enable() throws InterruptedException {
    since = Instant.now().toString();
    Thread.sleep(2);
    ens.getConsentBanner().setEnabled(true);
  }

  @AfterEach
  void restore() {
    ens.getConsentBanner().setEnabled(false);
    ens.getConsentBanner().setTitle("");
    ens.getConsentBanner().setText("");
    ens.getConsentBanner().setFile("");
    props.getX509().setEnabled(false);
    props.getX509().setAutoLogin(false);
    props.getX509().setTrustedCaFile("");
  }

  private MvcResult passwordLogin(MockHttpSession session, String username) throws Exception {
    var req = json(post("/api/auth/login"), Map.of("username", username, "password", "password"));
    return mvc.perform(session == null ? req : req.session(session)).andReturn();
  }

  private MockHttpSession acknowledge() throws Exception {
    MvcResult r = mvc.perform(post("/api/auth/consent").header("X-DSP-Request", "1")).andReturn();
    assertThat(status(r)).isEqualTo(200);
    assertThat(body(r).get("consentRequired").asBoolean()).isFalse();
    return (MockHttpSession) r.getRequest().getSession(false);
  }

  private List<JsonNode> events(String action) throws Exception {
    ens.getConsentBanner().setEnabled(false);
    Client admin = login("admin");
    List<JsonNode> out = new ArrayList<>();
    body(admin.get("/api/admin/audit?pageSize=100&action=" + action + "&from=" + URLEncoder.encode(since, StandardCharsets.UTF_8)))
        .get("events").forEach(out::add);
    return out;
  }

  @Test
  void publishesTheBannerWithTheStandardDodText() throws Exception {
    JsonNode consent = body(anonymous(get("/api/info"))).get("consent");
    assertThat(consent.get("title").asString()).isEqualTo("U.S. Government (USG) Information System (IS) Notice and Consent");
    assertThat(consent.get("text").asString())
        .startsWith("You are accessing a U.S. Government (USG) Information System (IS)")
        .contains("- The USG routinely intercepts and monitors communications on this IS");
    assertThat(body(anonymous(get("/api/auth/me"))).get("consentRequired").asBoolean()).isTrue();

    ens.getConsentBanner().setEnabled(false);
    assertThat(body(anonymous(get("/api/info"))).has("consent")).isFalse();
    assertThat(body(anonymous(get("/api/auth/me"))).get("consentRequired").asBoolean()).isFalse();
  }

  @Test
  void takesTheTextFromAFileOverTheSetting() throws Exception {
    Path file = Files.createTempFile("banner", ".txt");
    Files.writeString(file, "Site notice.\n\nSecond paragraph.\n");
    ens.getConsentBanner().setTitle("Notice");
    ens.getConsentBanner().setText("Ignored");
    ens.getConsentBanner().setFile(file.toString());
    JsonNode consent = body(anonymous(get("/api/info"))).get("consent");
    assertThat(consent.get("title").asString()).isEqualTo("Notice");
    assertThat(consent.get("text").asString()).isEqualTo("Site notice.\n\nSecond paragraph.");
  }

  @Test
  void refusesPasswordSignInUntilAcknowledged() throws Exception {
    MvcResult refused = passwordLogin(null, "alice");
    assertThat(status(refused)).isEqualTo(403);
    assertThat(body(refused).get("code").asString()).isEqualTo("consent_required");

    MockHttpSession session = acknowledge();
    assertThat(body(mvc.perform(get("/api/auth/me").session(session)).andReturn()).get("consentRequired").asBoolean()).isFalse();
    MvcResult ok = passwordLogin(session, "alice");
    assertThat(status(ok)).isEqualTo(200);
    MockHttpSession signedIn = (MockHttpSession) ok.getRequest().getSession(false);

    // Signing out ends the acknowledgement with the session: the banner comes before every sign-in.
    MvcResult out = mvc.perform(post("/api/auth/logout").header("X-DSP-Request", "1").session(signedIn)).andReturn();
    assertThat(body(out).get("consentRequired").asBoolean()).isTrue();
    assertThat(status(passwordLogin(signedIn, "alice"))).isEqualTo(403);

    List<JsonNode> denied = events("auth.login");
    assertThat(denied).anySatisfy(e -> {
      assertThat(e.get("outcome").asString()).isEqualTo("denied");
      assertThat(e.get("details").get("reasonCode").asString()).isEqualTo("consent_required");
      assertThat(e.get("actor").get("id").asString()).isEqualTo("alice");
    });
  }

  @Test
  void auditsTheAcknowledgementWithTheClientAddress() throws Exception {
    MvcResult r = mvc.perform(post("/api/auth/consent").header("X-DSP-Request", "1").with(req -> {
      req.setRemoteAddr("10.9.8.7");
      return req;
    })).andReturn();
    assertThat(status(r)).isEqualTo(200);
    List<JsonNode> acks = events("auth.consent-acknowledged");
    assertThat(acks).hasSize(1);
    JsonNode e = acks.get(0);
    assertThat(e.get("category").asString()).isEqualTo("authentication");
    assertThat(e.get("outcome").asString()).isEqualTo("success");
    assertThat(e.get("source").get("ip").asString()).isEqualTo("10.9.8.7");
  }

  @Test
  void needsTheRequestHeaderLikeEveryOtherChange() throws Exception {
    assertThat(status(mvc.perform(post("/api/auth/consent")).andReturn())).isEqualTo(403);
  }

  @Test
  void refusesCertificateSignInAndAutoLoginUntilAcknowledged() throws Exception {
    TestCertificates.Ca ca = TestCertificates.ca("CN=Consent Test CA");
    Path caFile = Files.createTempFile("ca", ".pem");
    Files.writeString(caFile, TestCertificates.pem(ca.cert()));
    props.getX509().setEnabled(true);
    props.getX509().setAutoLogin(true);
    props.getX509().setTrustedCaFile(caFile.toString());
    X509Certificate alice = TestCertificates.client("CN=alice").issuedBy(ca);

    MvcResult refused = mvc.perform(post("/api/auth/x509").header("X-DSP-Request", "1").with(x509(alice))).andReturn();
    assertThat(status(refused)).isEqualTo(403);
    assertThat(body(refused).get("code").asString()).isEqualTo("consent_required");

    // Automatic certificate sign-in waits for the banner too.
    JsonNode me = body(mvc.perform(get("/api/auth/me").with(x509(alice))).andReturn());
    assertThat(me.get("user").isNull()).isTrue();
    assertThat(me.get("consentRequired").asBoolean()).isTrue();

    MockHttpSession session = acknowledge();
    me = body(mvc.perform(get("/api/auth/me").session(session).with(x509(alice))).andReturn());
    assertThat(me.get("user").get("username").asString()).isEqualTo("alice");
  }
}
