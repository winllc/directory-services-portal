package com.winllc.dsp;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.winllc.dsp.ldap.EmbeddedDirectory;
import com.winllc.dsp.model.CustomSchema;
import com.winllc.dsp.store.ConfigStore;
import com.winllc.dsp.store.DemoConfigSeeder;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Boots the whole application against the embedded LDAP server. Directory data and portal
 * configuration are reset to the demo state before every test.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class ApiTestSupport {
  static final String B = "dc=example,dc=com";
  static final String ALICE = "uid=alice,ou=people," + B;

  @Autowired MockMvc mvc;
  @Autowired JsonMapper mapper;
  @Autowired ConfigStore store;
  @Autowired EmbeddedDirectory embedded;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) throws IOException {
    registry.add("portal.directory-mode", () -> "memory");
    registry.add("portal.data-dir", () -> tempDir());
    registry.add("portal.seed-demo-config", () -> "true");
  }

  private static String tempDir() {
    try {
      return Files.createTempDirectory("dsp-test").toString();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @BeforeEach
  void resetState() throws IOException {
    embedded.reset();
    DemoConfigSeeder.DemoConfig demo = DemoConfigSeeder.load(mapper);
    store.update(d -> {
      DemoConfigSeeder.apply(demo, d);
      d.customSchema = CustomSchema.empty();
      d.schemaCache = null;
      return null;
    });
  }

  static String q(String dn) {
    // Encode like the browser's encodeURIComponent (spaces as %20, not "+").
    return "?dn=" + java.net.URLEncoder.encode(dn, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** An authenticated client. URLs are used verbatim (query strings must already be encoded). */
  final class Client {
    final MockHttpSession session;
    final JsonNode user;

    Client(MockHttpSession session, JsonNode user) {
      this.session = session;
      this.user = user;
    }

    MvcResult send(MockHttpServletRequestBuilder req) throws Exception {
      return mvc.perform(req.session(session)).andReturn();
    }

    MvcResult get(String url) throws Exception {
      return send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(java.net.URI.create(url)));
    }

    MvcResult post(String url, Object body) throws Exception {
      return send(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(java.net.URI.create(url)), body));
    }

    MvcResult put(String url, Object body) throws Exception {
      return send(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(java.net.URI.create(url)), body));
    }

    MvcResult del(String url) throws Exception {
      return send(delete(java.net.URI.create(url)).header("X-DSP-Request", "1"));
    }
  }

  MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, Object body) throws Exception {
    return req.header("X-DSP-Request", "1").contentType(MediaType.APPLICATION_JSON)
        .content(body instanceof String s ? s : mapper.writeValueAsString(body == null ? Map.of() : body));
  }

  Client login(String username) throws Exception {
    MvcResult r = mvc.perform(json(post("/api/auth/login"), Map.of("username", username, "password", "password"))).andReturn();
    if (r.getResponse().getStatus() != 200) {
      throw new AssertionError("login failed for " + username + ": " + r.getResponse().getContentAsString());
    }
    return new Client((MockHttpSession) r.getRequest().getSession(false), body(r).get("user"));
  }

  JsonNode body(MvcResult r) throws Exception {
    String s = r.getResponse().getContentAsString();
    return s.isEmpty() ? mapper.nullNode() : mapper.readTree(s);
  }

  static int status(MvcResult r) {
    return r.getResponse().getStatus();
  }

  MvcResult anonymous(MockHttpServletRequestBuilder req) throws Exception {
    return mvc.perform(req).andReturn();
  }

  static MockHttpServletRequestBuilder anonGet(String url) {
    return get(url);
  }

  static MockHttpServletRequestBuilder anonPut(String url) {
    return put(url);
  }
}
