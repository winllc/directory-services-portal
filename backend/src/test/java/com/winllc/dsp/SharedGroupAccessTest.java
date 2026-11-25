package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.cluster.Cluster;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.groups.AccessRequests;
import com.winllc.dsp.groups.GroupService;
import com.winllc.dsp.ldap.Directories;
import com.winllc.dsp.service.EntryService;
import com.winllc.dsp.service.Memberships;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

/**
 * Group access with the shared database (PostgreSQL): its scheduled work happens once, whichever
 * replica runs it. A second "replica" is simulated with components built on the same database.
 */
@Testcontainers(disabledWithoutDocker = true)
class SharedGroupAccessTest extends ApiTestSupport {
  static final String ENGINEERING = "cn=engineering,ou=groups," + B;
  static final String DAVE = "uid=dave,ou=people," + B;

  @DynamicPropertySource
  static void sharedDatabase(DynamicPropertyRegistry registry) {
    SharedDatabaseSupport.register(registry);
  }

  @Autowired ApplicationContext context;
  @Autowired JdbcTemplate jdbc;
  @Autowired PortalProperties props;
  @Autowired AccessRequests requests;
  @Autowired GroupService groups;

  Cookie session;

  @BeforeEach
  void noRequests() {
    requests.clear();
    session = null;
  }

  /** Another replica: the same database, its own id. */
  Cluster otherReplica() {
    return new Cluster(context.getBeanProvider(JdbcTemplate.class), context.getBeanProvider(TransactionTemplate.class),
        context.getBeanProvider(com.winllc.dsp.cluster.SharedDocuments.class), context.getBeanProvider(LockProvider.class));
  }

  /** The other replica's group service, on its own view of the shared requests. */
  GroupService onOtherReplica(AccessRequests otherRequests) {
    return new GroupService(store, context.getBean(EntryService.class), context.getBean(Memberships.class), context.getBean(Directories.class),
        otherRequests, context.getBean(AuditLog.class));
  }

  /** Sends as the signed-in administrator (the session cookie only: Spring Session, no container session). */
  MvcResult send(MockHttpServletRequestBuilder req) throws Exception {
    if (session != null) req.cookie(session);
    MvcResult r = mvc.perform(req).andReturn();
    Cookie set = r.getResponse().getCookie("DSP_SESSION");
    if (set != null) session = set.getMaxAge() == 0 ? null : set;
    return r;
  }

  /** Saves the demo Groups definition with these group access settings, as the editor does. */
  void setGroups(Map<String, Object> settings) throws Exception {
    assertThat(status(send(json(post("/api/auth/login"), Map.of("username", "admin", "password", "password"))))).isEqualTo(200);
    JsonNode d = body(send(get("/api/admin/definitions"))).valueStream().filter(x -> x.get("id").asString().equals("def-groups")).findFirst()
        .orElseThrow();
    @SuppressWarnings("unchecked")
    Map<String, Object> in = new HashMap<>(mapper.convertValue(d, Map.class));
    in.keySet().removeAll(List.of("id", "createdAt", "updatedAt"));
    Map<String, Object> g = new HashMap<>(Map.of("enabled", true, "requireReason", false, "allowLeave", true));
    g.putAll(settings);
    in.put("groups", g);
    MvcResult r = send(json(put("/api/admin/definitions/def-groups"), in));
    assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
  }

  long auditEvents(String action, String text) {
    return jdbc.queryForObject("SELECT count(*) FROM dsp_audit_event WHERE line LIKE ? AND line LIKE ?", Long.class,
        "%\"action\":\"" + action + "\"%", "%" + text + "%");
  }

  @Test
  void aRequestExpiresOnceWhicheverReplicaRunsTheJob() throws Exception {
    setGroups(Map.of("requestExpiryDays", 7));
    AccessRequests.Request asked = requests.add("def-groups", ENGINEERING, "engineering", "dave", DAVE, "Dave Diaz", DAVE, "On-call rotation", 25);
    AccessRequests otherRequests = new AccessRequests(props, mapper, otherReplica());
    GroupService other = onOtherReplica(otherRequests);
    // The other replica sees the request, and neither expires it early.
    assertThat(otherRequests.find(asked.id()).pending()).isTrue();
    assertThat(other.expireRequests(Instant.now().plus(6, ChronoUnit.DAYS))).isZero();

    // Both replicas' jobs at the same moment (as when the cluster lock was lost): one closes it.
    Instant due = Instant.now().plus(8, ChronoUnit.DAYS);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> here = pool.submit(() -> groups.expireRequests(due));
      Future<Integer> there = pool.submit(() -> other.expireRequests(due));
      assertThat(here.get() + there.get()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(requests.find(asked.id()).status()).isEqualTo(AccessRequests.EXPIRED);
    assertThat(otherRequests.find(asked.id()).expiredAfterDays()).isEqualTo(7);
    assertThat(auditEvents("group.access-expired", asked.id())).isEqualTo(1);
    assertThat(groups.expireRequests(due.plusSeconds(60)) + other.expireRequests(due.plusSeconds(60))).isZero();
  }
}
