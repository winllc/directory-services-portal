package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.winllc.dsp.service.SessionService;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.rmi.server.RemoteObjectInvocationHandler;
import java.rmi.server.RemoteRef;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

/**
 * Sessions in the shared database are read back through an allow-list: every way of signing in
 * (password, OIDC, SAML) is saved and reloaded by a browser that only has the session cookie, and
 * a gadget written into spring_session_attributes is refused and reads as absent (signed out, not
 * a server error).
 */
@Testcontainers(disabledWithoutDocker = true)
class SharedSessionSerializationTest extends ApiTestSupport {
  static final TestOidcProvider OIDC;

  static {
    try {
      OIDC = new TestOidcProvider();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void shared(DynamicPropertyRegistry registry) throws Exception {
    SharedDatabaseSupport.register(registry);
    registry.add("portal.sso.oidc.issuer-uri", () -> OIDC.issuer);
    registry.add("portal.sso.oidc.client-id", () -> "portal");
    registry.add("portal.sso.oidc.client-secret", () -> "test-secret");
    SsoSamlTest.saml(registry);
  }

  @AfterAll
  static void stop() {
    OIDC.close();
  }

  @Autowired JdbcTemplate jdbc;

  /** Only cookies, as a browser: DSP_SESSION and DSP_SSO. */
  final class Browser {
    final Map<String, Cookie> cookies = new HashMap<>();

    MvcResult send(MockHttpServletRequestBuilder req) throws Exception {
      if (!cookies.isEmpty()) req.cookie(cookies.values().toArray(Cookie[]::new));
      MvcResult r = mvc.perform(req).andReturn();
      for (Cookie c : r.getResponse().getCookies()) {
        if (c.getMaxAge() == 0) cookies.remove(c.getName());
        else cookies.put(c.getName(), c);
      }
      return r;
    }

    JsonNode me() throws Exception {
      MvcResult r = send(get("/api/auth/me"));
      assertThat(status(r)).isEqualTo(200);
      return body(r).get("user");
    }
  }

  Browser password(String user) throws Exception {
    Browser b = new Browser();
    assertThat(status(b.send(json(post("/api/auth/login"), Map.of("username", user, "password", "password"))))).isEqualTo(200);
    return b;
  }

  @Test
  void everySignInIsSavedAndReloadedFromTheDatabase() throws Exception {
    assertThat(password("alice").me().get("authMethod").asString()).isEqualTo("password");

    Browser oidc = new Browser();
    MvcResult start = oidc.send(get("/api/auth/sso/authorize/oidc"));
    Map<String, String> q = SsoOidcTest.query(URI.create(start.getResponse().getRedirectedUrl()));
    OIDC.codes.put("c1", new TestOidcProvider.Grant(q.get("nonce"), q.get("code_challenge"), Map.of("preferred_username", "bob"), null, null));
    assertThat(oidc.send(get("/api/auth/sso/callback/oidc").param("code", "c1").param("state", q.get("state"))).getResponse().getRedirectedUrl())
        .isEqualTo("/");
    JsonNode bob = oidc.me();
    assertThat(bob.get("username").asString()).isEqualTo("bob");
    assertThat(bob.get("authMethod").asString()).isEqualTo("oidc");

    Browser saml = new Browser();
    MvcResult s = saml.send(get("/api/auth/sso/saml/authenticate/saml"));
    Map<String, String> sq = SsoOidcTest.query(URI.create(s.getResponse().getRedirectedUrl()));
    Matcher id = Pattern.compile(" ID=\"([^\"]+)\"").matcher(SsoSamlTest.inflate(Base64.getDecoder().decode(sq.get("SAMLRequest"))));
    assertThat(id.find()).isTrue();
    String xml = SsoSamlTest.response(id.group(1), "erin", SsoSamlTest.SP, SsoSamlTest.SIGNER, true);
    MvcResult acs = saml.send(post("/api/auth/sso/saml/acs/saml").param("RelayState", sq.get("RelayState"))
        .param("SAMLResponse", Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8))));
    assertThat(acs.getResponse().getRedirectedUrl()).isEqualTo("/");
    assertThat(saml.me().get("authMethod").asString()).isEqualTo("saml");
  }

  /** Replaces the security context of the signed-in session of {@code dn} with these bytes. */
  void tamper(String dn, Object gadget) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
      out.writeObject(gadget);
    }
    int n = jdbc.update("""
        UPDATE spring_session_attributes SET attribute_bytes = ? WHERE attribute_name = 'SPRING_SECURITY_CONTEXT'
          AND session_primary_id IN (SELECT primary_id FROM spring_session WHERE principal_name = ?)""",
        bytes.toByteArray(), com.winllc.dsp.audit.AuditJson.sha256Hex(dn.toLowerCase()));
    assertThat(n).isPositive();
  }

  @Test
  void aUrlKeyedHashMapIsRefusedAndReadsAsSignedOut() throws Exception {
    Browser carol = password("carol");
    assertThat(carol.me().isNull()).isFalse();
    Map<URL, String> gadget = new HashMap<>();
    gadget.put(URI.create("http://gadget.invalid/").toURL(), "x");
    tamper("uid=carol,ou=people," + B, gadget);
    assertThat(carol.me().isNull()).isTrue();
    assertThat(status(carol.send(get("/api/directories")))).isEqualTo(401);
  }

  @Test
  void anRmiInvocationHandlerIsRefusedAndReadsAsSignedOut() throws Exception {
    Browser dave = password("dave");
    assertThat(dave.me().isNull()).isFalse();
    RemoteObjectInvocationHandler handler = new RemoteObjectInvocationHandler(new TestRef());
    Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Serializable.class, List.class}, handler);
    tamper("uid=dave,ou=people," + B, proxy);
    assertThat(dave.me().isNull()).isTrue();
    // The handler on its own (refused by the java.rmi rule rather than at the proxy's interfaces).
    Browser again = password("dave");
    tamper("uid=dave,ou=people," + B, handler);
    assertThat(again.me().isNull()).isTrue();
  }

  @Test
  void theExpiryJobSkipsARefusedContextWithoutFailing() throws Exception {
    Browser george = password("george");
    assertThat(george.me().isNull()).isFalse();
    Map<URL, String> gadget = new HashMap<>();
    gadget.put(URI.create("http://gadget.invalid/").toURL(), "x");
    tamper("uid=george,ou=people," + B, gadget);
    jdbc.update("UPDATE spring_session SET expiry_time = 0 WHERE principal_name = ?",
        com.winllc.dsp.audit.AuditJson.sha256Hex(("uid=george,ou=people," + B).toLowerCase()));
    sessions.removeExpiredSessions();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE expiry_time = 0", Long.class)).isZero();
  }

  @Autowired SessionService sessions;

  /** A RemoteRef that serializes (its class is never reached: the handler is refused first). */
  public static final class TestRef implements RemoteRef {
    public TestRef() {}

    @Override
    public Object invoke(java.rmi.Remote obj, java.lang.reflect.Method method, Object[] params, long opnum) {
      return null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public java.rmi.server.RemoteCall newCall(java.rmi.server.RemoteObject obj, java.rmi.server.Operation[] op, int opnum, long hash) {
      return null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void invoke(java.rmi.server.RemoteCall call) {}

    @Override
    @SuppressWarnings("deprecation")
    public void done(java.rmi.server.RemoteCall call) {}

    @Override
    public String getRefClass(java.io.ObjectOutput out) {
      return null;
    }

    @Override
    public int remoteHashCode() {
      return 0;
    }

    @Override
    public boolean remoteEquals(RemoteRef obj) {
      return obj == this;
    }

    @Override
    public String remoteToString() {
      return "test";
    }

    @Override
    public void writeExternal(java.io.ObjectOutput out) {}

    @Override
    public void readExternal(java.io.ObjectInput in) {}
  }
}
