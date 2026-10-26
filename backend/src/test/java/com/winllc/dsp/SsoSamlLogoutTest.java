package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.winllc.dsp.cluster.SessionAttributes;
import com.winllc.dsp.sso.SamlLogout;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.saml2.core.OpenSamlInitializationService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * SAML single logout (SSO_SAML_SINGLE_LOGOUT with this SP's signing credential, against an IdP
 * whose metadata offers a SingleLogoutService by HTTP-Redirect): signing out of the portal after
 * a SAML sign-in answers where the browser goes next, a signed LogoutRequest for the IdP's session.
 */
class SsoSamlLogoutTest extends ApiTestSupport {
  static final String IDP = SsoSamlTest.IDP;
  static final String SP = SsoSamlTest.SP;
  static final TestCertificates.Ca SP_KEYS = TestCertificates.ca("CN=Test SP");

  static {
    OpenSamlInitializationService.initialize();
  }

  @DynamicPropertySource
  static void saml(DynamicPropertyRegistry registry) throws Exception {
    Path metadata = Files.createTempFile("idp-metadata", ".xml");
    String cert = Base64.getEncoder().encodeToString(SsoSamlTest.SIGNER.cert().getEncoded());
    Files.writeString(metadata, """
        <md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" xmlns:ds="http://www.w3.org/2000/09/xmldsig#" entityID="%s">
          <md:IDPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
            <md:KeyDescriptor use="signing"><ds:KeyInfo><ds:X509Data><ds:X509Certificate>%s</ds:X509Certificate></ds:X509Data></ds:KeyInfo></md:KeyDescriptor>
            <md:SingleLogoutService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect" Location="%s/slo"/>
            <md:SingleSignOnService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect" Location="%s/sso"/>
          </md:IDPSSODescriptor>
        </md:EntityDescriptor>""".formatted(IDP, cert, IDP, IDP));
    Path key = Files.createTempFile("sp-signing", ".key");
    Path crt = Files.createTempFile("sp-signing", ".crt");
    Base64.Encoder pem = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
    Files.writeString(key, "-----BEGIN PRIVATE KEY-----\n" + pem.encodeToString(SP_KEYS.keys().getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n");
    Files.writeString(crt, "-----BEGIN CERTIFICATE-----\n" + pem.encodeToString(SP_KEYS.cert().getEncoded()) + "\n-----END CERTIFICATE-----\n");
    registry.add("portal.sso.saml.metadata-location", metadata::toString);
    registry.add("portal.sso.public-url", () -> "http://localhost");
    registry.add("portal.sso.saml.signing-key-file", key::toString);
    registry.add("portal.sso.saml.signing-certificate-file", crt::toString);
    registry.add("portal.sso.saml.single-logout", () -> "true");
  }

  /** Signs alice in through the IdP; returns her portal session. */
  MockHttpSession signIn() throws Exception {
    MvcResult r = mvc.perform(get("/api/auth/sso/saml/authenticate/saml")).andReturn();
    URI location = URI.create(r.getResponse().getRedirectedUrl());
    var q = SsoOidcTest.query(location);
    Matcher id = Pattern.compile(" ID=\"([^\"]+)\"").matcher(SsoSamlTest.inflate(Base64.getDecoder().decode(q.get("SAMLRequest"))));
    assertThat(id.find()).isTrue();
    Cookie sso = r.getResponse().getCookie("DSP_SSO");
    String xml = SsoSamlTest.response(id.group(1), "alice", SP, SsoSamlTest.SIGNER, true);
    MvcResult in = mvc.perform(post("/api/auth/sso/saml/acs/saml")
        .param("SAMLResponse", Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8)))
        .param("RelayState", q.get("RelayState")).cookie(sso)).andReturn();
    assertThat(in.getResponse().getRedirectedUrl()).isEqualTo("/");
    return (MockHttpSession) in.getRequest().getSession(false);
  }

  @Test
  void thisSpsMetadataListsItsSingleLogoutService() throws Exception {
    String m = mvc.perform(get("/api/auth/sso/saml/metadata")).andReturn().getResponse().getContentAsString();
    assertThat(m).contains("SingleLogoutService").contains("http://localhost/api/auth/sso/saml/logout");
  }

  @Test
  void signingOutSendsASignedLogoutRequestForTheIdpsSession() throws Exception {
    MockHttpSession session = signIn();
    MvcResult out = mvc.perform(post("/api/auth/logout").session(session).header("X-DSP-Request", "1")).andReturn();
    assertThat(status(out)).isEqualTo(200);
    JsonNode body = body(out);
    // The portal's session is over before the browser leaves.
    assertThat(session.isInvalid()).isTrue();
    URI next = URI.create(body.get("redirect").asString());
    assertThat(next.toString()).startsWith(IDP + "/slo?");
    var q = SsoOidcTest.query(next);
    assertThat(q).containsKeys("SAMLRequest", "RelayState", "SigAlg", "Signature");
    String request = SsoSamlTest.inflate(Base64.getDecoder().decode(q.get("SAMLRequest")));
    assertThat(request).contains("LogoutRequest").contains("Destination=\"" + IDP + "/slo\"").contains(">alice<").contains(">" + SP + "<");
    // Signed with this SP's key (HTTP-Redirect: over the encoded query parameters, in order).
    String raw = next.getRawQuery();
    String signed = raw.substring(0, raw.indexOf("&Signature="));
    Signature verify = Signature.getInstance("SHA256withRSA");
    verify.initVerify(SP_KEYS.cert().getPublicKey());
    verify.update(signed.getBytes(StandardCharsets.US_ASCII));
    String sig = URLDecoder.decode(raw.substring(raw.indexOf("&Signature=") + 11), StandardCharsets.UTF_8);
    assertThat(q.get("SigAlg")).isEqualTo("http://www.w3.org/2001/04/xmldsig-more#rsa-sha256");
    assertThat(verify.verify(Base64.getDecoder().decode(sig))).isTrue();

    // The IdP sends the browser back; it goes on to the login page.
    MvcResult back = mvc.perform(get("/api/auth/sso/saml/logout").param("SAMLResponse", "x")).andReturn();
    assertThat(back.getResponse().getRedirectedUrl()).isEqualTo("/login?signed_out");
  }

  @Test
  void aPasswordSignInSignsOutHereOnly() throws Exception {
    Client alice = login("alice");
    assertThat(body(alice.post("/api/auth/logout", java.util.Map.of())).has("redirect")).isFalse();
  }

  @Test
  void theSessionKeepsOnlyIdentifiersAndReadsThemBack() throws Exception {
    SamlLogout logout = new SamlLogout("alice", "urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified", List.of("_s1", "_s2"));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
      out.writeObject(logout);
    }
    assertThat(SessionAttributes.read(bytes.toByteArray(), getClass().getClassLoader())).isEqualTo(logout);
  }
}
