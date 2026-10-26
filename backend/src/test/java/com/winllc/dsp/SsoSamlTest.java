package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.winllc.dsp.sso.SsoStateStore;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import javax.xml.namespace.QName;
import net.shibboleth.shared.xml.SerializeSupport;
import org.junit.jupiter.api.Test;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Audience;
import org.opensaml.saml.saml2.core.AudienceRestriction;
import org.opensaml.saml.saml2.core.AuthnContext;
import org.opensaml.saml.saml2.core.AuthnContextClassRef;
import org.opensaml.saml.saml2.core.AuthnStatement;
import org.opensaml.saml.saml2.core.Conditions;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.Status;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.saml.saml2.core.Subject;
import org.opensaml.saml.saml2.core.SubjectConfirmation;
import org.opensaml.saml.saml2.core.SubjectConfirmationData;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.signature.Signature;
import org.opensaml.xmlsec.signature.support.SignatureConstants;
import org.opensaml.xmlsec.signature.support.Signer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.saml2.core.OpenSamlInitializationService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * SAML 2.0 sign-in against a test IdP whose metadata is a file: a signed response for this
 * portal's request signs in the mapped directory user; unsigned, unsolicited, misaddressed or
 * reused responses are refused.
 */
class SsoSamlTest extends ApiTestSupport {
  static final String IDP = "https://idp.test";
  static final String SP = "http://localhost/api/auth/sso/saml/metadata";
  static final String ACS = "http://localhost/api/auth/sso/saml/acs/saml";
  static final TestCertificates.Ca SIGNER = TestCertificates.ca("CN=Test IdP");
  /** The IdP's certificate with another key (TestCertificates signs every CA with one key pair). */
  static final TestCertificates.Ca OTHER = new TestCertificates.Ca(SIGNER.cert(), otherKeys());

  static java.security.KeyPair otherKeys() {
    try {
      java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("RSA");
      g.initialize(2048);
      return g.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static {
    OpenSamlInitializationService.initialize();
  }

  @DynamicPropertySource
  static void saml(DynamicPropertyRegistry registry) throws Exception {
    Path metadata = Files.createTempFile("idp-metadata", ".xml");
    String cert = Base64.getEncoder().encodeToString(SIGNER.cert().getEncoded());
    Files.writeString(metadata, """
        <md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" xmlns:ds="http://www.w3.org/2000/09/xmldsig#" entityID="%s">
          <md:IDPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
            <md:KeyDescriptor use="signing"><ds:KeyInfo><ds:X509Data><ds:X509Certificate>%s</ds:X509Certificate></ds:X509Data></ds:KeyInfo></md:KeyDescriptor>
            <md:SingleSignOnService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect" Location="%s/sso"/>
          </md:IDPSSODescriptor>
        </md:EntityDescriptor>""".formatted(IDP, cert, IDP));
    registry.add("portal.sso.saml.metadata-location", metadata::toString);
    registry.add("portal.sso.saml.label", () -> "Sign in with Test IdP");
    registry.add("portal.sso.public-url", () -> "http://localhost");
  }

  @Autowired SsoStateStore state;

  record Started(Cookie sso, String requestId, String relayState) {}

  Started start() throws Exception {
    MvcResult r = mvc.perform(get("/api/auth/sso/saml/authenticate/saml")).andReturn();
    assertThat(status(r)).isEqualTo(302);
    URI location = URI.create(r.getResponse().getRedirectedUrl());
    assertThat(location.toString()).startsWith(IDP + "/sso?");
    var q = SsoOidcTest.query(location);
    String xml = inflate(Base64.getDecoder().decode(q.get("SAMLRequest")));
    Matcher id = Pattern.compile(" ID=\"([^\"]+)\"").matcher(xml);
    assertThat(id.find()).isTrue();
    assertThat(xml).contains(ACS);
    Cookie sso = r.getResponse().getCookie("DSP_SSO");
    assertThat(sso).isNotNull();
    return new Started(sso, id.group(1), q.get("RelayState"));
  }

  MvcResult answer(Started s, String xml, boolean withCookie) throws Exception {
    var req = post("/api/auth/sso/saml/acs/saml").param("SAMLResponse", Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8)));
    if (s != null && s.relayState() != null) req.param("RelayState", s.relayState());
    if (withCookie && s != null) req.cookie(s.sso());
    return mvc.perform(req).andReturn();
  }

  @Test
  void servesItsMetadataAndOffersTheProvider() throws Exception {
    MvcResult m = mvc.perform(get("/api/auth/sso/saml/metadata")).andReturn();
    assertThat(status(m)).isEqualTo(200);
    assertThat(m.getResponse().getContentAsString()).contains("entityID=\"" + SP + "\"").contains(ACS).doesNotContain("SingleLogoutService");
    JsonNode sso = body(anonymous(get("/api/info"))).get("auth").get("sso");
    assertThat(sso.get(0).get("url").asString()).isEqualTo("/api/auth/sso/saml/authenticate/saml");
  }

  @Test
  void aSignedResponseSignsInTheMappedUser() throws Exception {
    Started s = start();
    MvcResult r = answer(s, response(s.requestId(), "alice", SP, SIGNER, true), true);
    assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/");
    JsonNode user = body(mvc.perform(get("/api/auth/me").session((MockHttpSession) r.getRequest().getSession(false))).andReturn()).get("user");
    assertThat(user.get("username").asString()).isEqualTo("alice");
    assertThat(user.get("authMethod").asString()).isEqualTo("saml");
    // Without single logout (SSO_SAML_SINGLE_LOGOUT, off by default) signing out ends the
    // portal's session only, as before: nothing to go on to, nothing kept for it.
    MockHttpSession session = (MockHttpSession) r.getRequest().getSession(false);
    assertThat(session.getAttribute(com.winllc.dsp.sso.SamlLogout.ATTRIBUTE)).isNull();
    MvcResult out = mvc.perform(post("/api/auth/logout").session(session).header("X-DSP-Request", "1")).andReturn();
    assertThat(status(out)).isEqualTo(200);
    assertThat(body(out).has("redirect")).isFalse();
    assertThat(status(mvc.perform(get("/api/auth/sso/saml/logout")).andReturn())).isEqualTo(404);
  }

  @Test
  void refusesResponsesThatDoNotCheckOut() throws Exception {
    Started unsigned = start();
    assertThat(answer(unsigned, response(unsigned.requestId(), "alice", SP, null, true), true).getResponse().getRedirectedUrl())
        .isEqualTo("/login?sso_error=failed");
    Started forged = start();
    assertThat(answer(forged, response(forged.requestId(), "alice", SP, OTHER, true), true).getResponse().getRedirectedUrl())
        .isEqualTo("/login?sso_error=failed");
    Started audience = start();
    assertThat(answer(audience, response(audience.requestId(), "alice", "https://other-sp.example", SIGNER, true), true).getResponse()
        .getRedirectedUrl()).isEqualTo("/login?sso_error=failed");
    // IdP-initiated: answers no request of this browser.
    assertThat(answer(null, response(null, "alice", SP, SIGNER, true), false).getResponse().getRedirectedUrl())
        .isEqualTo("/login?sso_error=failed");
    // Without this browser's DSP_SSO cookie.
    Started stolen = start();
    assertThat(answer(stolen, response(stolen.requestId(), "alice", SP, SIGNER, true), false).getResponse().getRedirectedUrl())
        .isEqualTo("/login?sso_error=failed");
    // No directory entry for the NameID.
    Started nobody = start();
    assertThat(answer(nobody, response(nobody.requestId(), "nobody", SP, SIGNER, true), true).getResponse().getRedirectedUrl())
        .isEqualTo("/login?sso_error=no_account");
  }

  @Test
  void aForgedHostCannotChangeTheEntityIdOrAudience() throws Exception {
    MvcResult m = mvc.perform(get("/api/auth/sso/saml/metadata").header("Forwarded", "host=dsp-test.example;proto=https")
        .header("X-Forwarded-Host", "dsp-test.example")).andReturn();
    assertThat(m.getResponse().getContentAsString()).contains("entityID=\"" + SP + "\"").contains(ACS).doesNotContain("dsp-test.example");
    // An assertion the IdP issued to another SP (another portal on the same IdP) is refused, even
    // when the request claims that portal's host.
    Started s = start();
    String other = "https://dsp-test.example/api/auth/sso/saml/metadata";
    var req = post("/api/auth/sso/saml/acs/saml")
        .param("SAMLResponse", Base64.getEncoder().encodeToString(response(s.requestId(), "alice", other, SIGNER, true).getBytes(StandardCharsets.UTF_8)))
        .param("RelayState", s.relayState()).cookie(s.sso())
        .header("Forwarded", "host=dsp-test.example;proto=https").header("X-Forwarded-Host", "dsp-test.example").header("X-Forwarded-Proto", "https");
    assertThat(mvc.perform(req).andReturn().getResponse().getRedirectedUrl()).endsWith("/login?sso_error=failed");
  }

  @Test
  void anAssertionIsAcceptedOnce() {
    String id = "_" + UUID.randomUUID();
    assertThat(state.firstUse(id, Instant.now().plusSeconds(300))).isTrue();
    assertThat(state.firstUse(id, Instant.now().plusSeconds(300))).isFalse();
  }

  // ---------------------------------------------------------------------------
  // A test IdP's responses
  // ---------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  static <T extends XMLObject> T build(QName name) {
    return (T) XMLObjectProviderRegistrySupport.getBuilderFactory().ensureBuilder(name).buildObject(name);
  }

  static String response(String inResponseTo, String nameId, String audience, TestCertificates.Ca signer, boolean signAssertion)
      throws Exception {
    Instant now = Instant.now();
    Response response = build(Response.DEFAULT_ELEMENT_NAME);
    response.setID("_" + UUID.randomUUID());
    response.setIssueInstant(now);
    response.setDestination(ACS);
    response.setInResponseTo(inResponseTo);
    response.setIssuer(issuer());
    Status status = build(Status.DEFAULT_ELEMENT_NAME);
    StatusCode code = build(StatusCode.DEFAULT_ELEMENT_NAME);
    code.setValue(StatusCode.SUCCESS);
    status.setStatusCode(code);
    response.setStatus(status);

    Assertion assertion = build(Assertion.DEFAULT_ELEMENT_NAME);
    assertion.setID("_" + UUID.randomUUID());
    assertion.setIssueInstant(now);
    assertion.setIssuer(issuer());
    Subject subject = build(Subject.DEFAULT_ELEMENT_NAME);
    NameID name = build(NameID.DEFAULT_ELEMENT_NAME);
    name.setValue(nameId);
    subject.setNameID(name);
    SubjectConfirmation confirmation = build(SubjectConfirmation.DEFAULT_ELEMENT_NAME);
    confirmation.setMethod(SubjectConfirmation.METHOD_BEARER);
    SubjectConfirmationData data = build(SubjectConfirmationData.DEFAULT_ELEMENT_NAME);
    data.setRecipient(ACS);
    data.setNotOnOrAfter(now.plusSeconds(300));
    data.setInResponseTo(inResponseTo);
    confirmation.setSubjectConfirmationData(data);
    subject.getSubjectConfirmations().add(confirmation);
    assertion.setSubject(subject);
    Conditions conditions = build(Conditions.DEFAULT_ELEMENT_NAME);
    conditions.setNotBefore(now.minusSeconds(60));
    conditions.setNotOnOrAfter(now.plusSeconds(300));
    AudienceRestriction restriction = build(AudienceRestriction.DEFAULT_ELEMENT_NAME);
    Audience aud = build(Audience.DEFAULT_ELEMENT_NAME);
    aud.setURI(audience);
    restriction.getAudiences().add(aud);
    conditions.getAudienceRestrictions().add(restriction);
    assertion.setConditions(conditions);
    AuthnStatement authn = build(AuthnStatement.DEFAULT_ELEMENT_NAME);
    authn.setAuthnInstant(now);
    AuthnContext context = build(AuthnContext.DEFAULT_ELEMENT_NAME);
    AuthnContextClassRef ref = build(AuthnContextClassRef.DEFAULT_ELEMENT_NAME);
    ref.setURI(AuthnContext.PPT_AUTHN_CTX);
    context.setAuthnContextClassRef(ref);
    authn.setAuthnContext(context);
    assertion.getAuthnStatements().add(authn);
    response.getAssertions().add(assertion);

    Signature signature = null;
    if (signer != null && signAssertion) {
      signature = build(Signature.DEFAULT_ELEMENT_NAME);
      signature.setSigningCredential(new BasicX509Credential(signer.cert(), signer.keys().getPrivate()));
      signature.setSignatureAlgorithm(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
      signature.setCanonicalizationAlgorithm(SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
      assertion.setSignature(signature);
    }
    var element = XMLObjectProviderRegistrySupport.getMarshallerFactory().getMarshaller(response).marshall(response);
    if (signature != null) Signer.signObject(signature);
    return SerializeSupport.nodeToString(element);
  }

  private static Issuer issuer() {
    Issuer issuer = build(Issuer.DEFAULT_ELEMENT_NAME);
    issuer.setValue(IDP);
    return issuer;
  }

  static String inflate(byte[] deflated) throws Exception {
    Inflater inflater = new Inflater(true);
    inflater.setInput(deflated);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    while (!inflater.finished()) {
      int n = inflater.inflate(buf);
      if (n == 0 && inflater.needsInput()) break;
      out.write(buf, 0, n);
    }
    return out.toString(StandardCharsets.UTF_8);
  }
}
