package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.config.EnsProperties;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.ldap.LdapDirectory;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.service.SessionService;
import jakarta.servlet.http.HttpSessionEvent;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Session controls (ENS_SESSION_*), the configurable lockout, group refresh and the audit check
 * before directory writes (DISA STIG sign-in and session requirements).
 */
class SessionControlsTest extends ApiTestSupport {
  static final String ADMINS = "cn=directory-admins,ou=groups," + B;

  @Autowired EnsProperties ens;
  @Autowired PortalProperties props;
  @Autowired SessionService sessions;
  @Autowired LdapDirectory directory;
  @Autowired AuditLog audit;
  private String since;

  @BeforeEach
  void mark() throws InterruptedException {
    since = Instant.now().toString();
    Thread.sleep(2);
  }

  @AfterEach
  void restore() {
    ens.getSession().setIdleTimeout(Duration.ofMinutes(30));
    ens.getSession().setAdminIdleTimeout(null);
    ens.getSession().setAbsoluteTimeout(Duration.ofHours(12));
    ens.getSession().setMaxPerUser(0);
    props.getLogin().setMaxFailures(8);
    props.getLogin().setLockoutWindow(Duration.ofMinutes(15));
    props.setGroupRefreshInterval(Duration.ofMinutes(5));
  }

  private List<JsonNode> events(String action) throws Exception {
    Client admin = login("admin");
    List<JsonNode> out = new ArrayList<>();
    body(admin.get("/api/admin/audit?pageSize=100&action=" + action + "&from=" + URLEncoder.encode(since, StandardCharsets.UTF_8)))
        .get("events").forEach(out::add);
    return out;
  }

  private static void ago(MockHttpSession session, String attribute, Duration d) {
    session.setAttribute(attribute, System.currentTimeMillis() - d.toMillis());
  }

  private static void assertExpired(MvcResult r) throws Exception {
    assertThat(status(r)).isEqualTo(401);
    assertThat(r.getResponse().getContentAsString()).contains("\"code\":\"session_expired\"");
  }

  @Test
  void givesAdministratorsTheirOwnIdleTimeout() throws Exception {
    ens.getSession().setIdleTimeout(Duration.ofMinutes(30));
    ens.getSession().setAdminIdleTimeout(Duration.ofMinutes(15));
    assertThat(login("alice").session.getMaxInactiveInterval()).isEqualTo(30 * 60);
    assertThat(login("admin").session.getMaxInactiveInterval()).isEqualTo(15 * 60);
  }

  @Test
  void endsIdleSessionsAndCountsOnlyUserActivity() throws Exception {
    ens.getSession().setAdminIdleTimeout(Duration.ofMinutes(15));
    Client alice = login("alice");
    ago(alice.session, SessionService.LAST_ACTIVITY, Duration.ofMinutes(20));
    // Background polling neither ends a session that isn't past its timeout nor counts as activity.
    assertThat(status(alice.send(get("/api/jobs").header(SessionService.BACKGROUND_HEADER, "1")))).isEqualTo(200);
    long last = (Long) alice.session.getAttribute(SessionService.LAST_ACTIVITY);
    assertThat(System.currentTimeMillis() - last).isGreaterThanOrEqualTo(Duration.ofMinutes(20).toMillis());
    assertThat(status(alice.get("/api/definitions"))).isEqualTo(200);
    assertThat(System.currentTimeMillis() - (Long) alice.session.getAttribute(SessionService.LAST_ACTIVITY)).isLessThan(60_000);

    // An administrator idle for 20 minutes is past the 15-minute administrator timeout.
    Client admin = login("admin");
    ago(admin.session, SessionService.LAST_ACTIVITY, Duration.ofMinutes(20));
    assertExpired(admin.send(get("/api/jobs").header(SessionService.BACKGROUND_HEADER, "1")));
    assertThat(admin.session.isInvalid()).isTrue();

    Client bob = login("bob");
    ago(bob.session, SessionService.LAST_ACTIVITY, Duration.ofMinutes(31));
    assertExpired(bob.get("/api/definitions"));

    List<JsonNode> expired = events("auth.session-expired");
    assertThat(expired).extracting(e -> e.get("actor").get("id").asString() + ":" + e.get("reason").asString())
        .containsExactlyInAnyOrder("admin:idle", "bob:idle");
    assertThat(expired.get(0).get("category").asString()).isEqualTo("authentication");
  }

  @Test
  void endsSessionsAfterTheirAbsoluteLifetime() throws Exception {
    ens.getSession().setAbsoluteTimeout(Duration.ofHours(12));
    Client alice = login("alice");
    ago(alice.session, SessionService.SIGNED_IN_AT, Duration.ofHours(12).plusMinutes(1));
    assertExpired(alice.get("/api/definitions"));
    assertThat(events("auth.session-expired")).anySatisfy(e -> assertThat(e.get("reason").asString()).isEqualTo("absolute"));
  }

  @Test
  void reportsAnExpiredSessionAsSignedOutOnTheSessionCheck() throws Exception {
    Client alice = login("alice");
    ago(alice.session, SessionService.LAST_ACTIVITY, Duration.ofHours(1));
    MvcResult me = alice.get("/api/auth/me");
    assertThat(status(me)).isEqualTo(200);
    assertThat(body(me).get("user").isNull()).isTrue();
  }

  @Test
  void expiresTheOldestSessionOverTheLimit() throws Exception {
    ens.getSession().setMaxPerUser(2);
    Client first = login("erin");
    Client second = login("erin");
    Client third = login("erin");
    assertThat(sessions.sessionsOf(new SessionUser("erin", "uid=erin,ou=people," + B, null, null, List.of(), false, null, null))).isEqualTo(2);
    // Ended at once: its next request is simply no longer signed in.
    assertThat(first.session.isInvalid()).isTrue();
    assertThat(status(first.get("/api/definitions"))).isEqualTo(401);
    assertThat(status(second.get("/api/definitions"))).isEqualTo(200);
    assertThat(status(third.get("/api/definitions"))).isEqualTo(200);
    // (Sessions of other tests in this context that erin never signed out of are expired too.)
    ens.getSession().setMaxPerUser(0);
    assertThat(events("auth.session-expired")).isNotEmpty().allSatisfy(e -> {
      assertThat(e.get("reason").asString()).isEqualTo("concurrent");
      assertThat(e.get("actor").get("id").asString()).isEqualTo("erin");
    });
  }

  @Test
  void auditsContainerTimeoutsButNotShutdownOrSignOut() throws Exception {
    Client alice = login("alice");
    SessionUser user = (SessionUser) ((SecurityContext) alice.session
        .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).getAuthentication().getPrincipal();
    MockHttpSession timedOut = new MockHttpSession() {
      @Override
      public long getLastAccessedTime() {
        return System.currentTimeMillis() - 31 * 60_000L;
      }
    };
    timedOut.setMaxInactiveInterval(30 * 60);
    timedOut.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, SessionService.context(user));
    sessions.sessionDestroyed(new HttpSessionEvent(timedOut));
    // Still fresh, e.g. expired at shutdown: not a timeout.
    MockHttpSession fresh = new MockHttpSession();
    fresh.setMaxInactiveInterval(30 * 60);
    fresh.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, SessionService.context(user));
    sessions.sessionDestroyed(new HttpSessionEvent(fresh));
    assertThat(status(alice.post("/api/auth/logout", null))).isEqualTo(200);

    assertThat(events("auth.session-expired")).singleElement().satisfies(e -> {
      assertThat(e.get("reason").asString()).isEqualTo("idle");
      assertThat(e.get("actor").get("id").asString()).isEqualTo("alice");
    });
  }

  @Test
  void readsGroupsAndAdministratorStatusAgainWhenDue() throws Exception {
    props.setGroupRefreshInterval(Duration.ofMinutes(5));
    Client alice = login("alice");
    assertThat(status(alice.get("/api/admin/forms"))).isEqualTo(403);
    directory.modify(ADMINS, List.of(new Modification(ModificationType.ADD, "member", ALICE)));

    // Not due yet: the session keeps what it had at sign-in.
    assertThat(status(alice.get("/api/admin/forms"))).isEqualTo(403);
    ago(alice.session, SessionService.GROUPS_RESOLVED_AT, Duration.ofMinutes(6));
    assertThat(status(alice.get("/api/admin/forms"))).isEqualTo(200);
    assertThat(body(alice.get("/api/auth/me")).get("user").get("isAdmin").asBoolean()).isTrue();

    directory.modify(ADMINS, List.of(new Modification(ModificationType.DELETE, "member", ALICE)));
    ago(alice.session, SessionService.GROUPS_RESOLVED_AT, Duration.ofMinutes(6));
    assertThat(status(alice.get("/api/admin/forms"))).isEqualTo(403);
  }

  @Test
  void endsTheSessionOfAnAccountThatNoLongerExists() throws Exception {
    Client frank = login("frank");
    directory.delete("uid=frank,ou=partners," + B);
    ago(frank.session, SessionService.GROUPS_RESOLVED_AT, Duration.ofMinutes(6));
    assertExpired(frank.get("/api/definitions"));
    assertThat(events("auth.session-expired")).anySatisfy(e -> assertThat(e.get("reason").asString()).isEqualTo("account-removed"));
  }

  @Test
  void locksOutAfterTheConfiguredNumberOfFailures() throws Exception {
    props.getLogin().setMaxFailures(3);
    props.getLogin().setLockoutWindow(Duration.ofMinutes(30));
    for (int i = 0; i < 3; i++) {
      MvcResult r = anonymous(json(post("/api/auth/login"), Map.of("username", "dave", "password", "wrong")).with(req -> {
        req.setRemoteAddr("10.4.4.4");
        return req;
      }));
      assertThat(status(r)).isEqualTo(401);
    }
    MvcResult locked = anonymous(json(post("/api/auth/login"), Map.of("username", "dave", "password", "password")).with(req -> {
      req.setRemoteAddr("10.4.4.4");
      return req;
    }));
    assertThat(status(locked)).isEqualTo(429);
    assertThat(events("auth.lockout")).singleElement().satisfies(e -> {
      assertThat(e.get("details").get("failures").asInt()).isEqualTo(3);
      assertThat(e.get("details").get("windowMinutes").asInt()).isEqualTo(30);
      assertThat(e.get("reason").asString()).contains("refused for 30 minutes");
    });
  }

  @Test
  void refusesDirectoryWritesItCannotAudit() throws Exception {
    Client erin = login("erin");
    Path store = audit.store().dir();
    Path aside = store.resolveSibling(store.getFileName() + ".aside");
    Files.move(store, aside);
    Files.writeString(store, "not a directory");
    MvcResult r;
    try {
      r = erin.put("/api/directories/def-staff/entry" + q(ALICE), Map.of("values", Map.of("title", "Unaudited")));
    } finally {
      Files.delete(store);
      Files.move(aside, store);
    }
    assertThat(status(r)).isEqualTo(503);
    assertThat(directory.get(ALICE, List.of("title")).values("title")).doesNotContain("Unaudited");
  }
}
