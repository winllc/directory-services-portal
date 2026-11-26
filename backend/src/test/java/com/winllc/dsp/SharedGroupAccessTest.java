package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.cluster.Cluster;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.groups.GovernanceClient;
import com.winllc.dsp.groups.AccessRequests;
import com.winllc.dsp.groups.GroupService;
import com.winllc.dsp.groups.MembershipExpiries;
import com.winllc.dsp.ldap.Directories;
import com.winllc.dsp.service.EntryService;
import com.winllc.dsp.service.Memberships;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * replica runs it, and the end dates of memberships are rows every replica reads. A second
 * "replica" is simulated with components built on the same database.
 */
@Testcontainers(disabledWithoutDocker = true)
class SharedGroupAccessTest extends ApiTestSupport {
  static final String ENGINEERING = "cn=engineering,ou=groups," + B;
  static final String SALES = "cn=sales,ou=groups," + B;
  static final String DAVE = "uid=dave,ou=people," + B;
  static final String GEORGE = "uid=george,ou=people," + B;

  @DynamicPropertySource
  static void sharedDatabase(DynamicPropertyRegistry registry) {
    SharedDatabaseSupport.register(registry);
  }

  @Autowired ApplicationContext context;
  @Autowired JdbcTemplate jdbc;
  @Autowired PortalProperties props;
  @Autowired AccessRequests requests;
  @Autowired GroupService groups;
  @Autowired MembershipExpiries expiries;

  Cookie session;

  @BeforeEach
  void noRequests() {
    requests.clear();
    expiries.clearAll();
    jdbc.update("DELETE FROM dsp_document WHERE name = 'group-membership-expiries-imported'");
    session = null;
  }

  /** Another replica: the same database, its own id. */
  Cluster otherReplica() {
    return new Cluster(context.getBeanProvider(JdbcTemplate.class), context.getBeanProvider(TransactionTemplate.class),
        context.getBeanProvider(com.winllc.dsp.cluster.SharedDocuments.class), context.getBeanProvider(LockProvider.class));
  }

  /** The other replica's group service, on its own view of the shared requests and end dates. */
  GroupService onOtherReplica(AccessRequests otherRequests, MembershipExpiries otherExpiries) {
    return new GroupService(store, context.getBean(EntryService.class), context.getBean(Memberships.class), context.getBean(Directories.class),
        otherRequests, otherExpiries, context.getBean(AuditLog.class), context.getBean(GovernanceClient.class));
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
    GroupService other = onOtherReplica(otherRequests, new MembershipExpiries(props, mapper, otherReplica()));
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

  @Test
  void aMembershipEndsOnceWhicheverReplicaRunsTheJob() throws Exception {
    setGroups(Map.of());
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate last = today.plusDays(5);
    // The administrator adds george to engineering until a date: a row, not a file.
    MvcResult added = send(json(post(java.net.URI.create("/api/groups/def-groups/members" + q(ENGINEERING))),
        Map.of("add", List.of(GEORGE), "remove", List.of(), "until", last.toString())));
    assertThat(status(added)).as(added.getResponse().getContentAsString()).isEqualTo(200);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dsp_group_membership_expiry", Long.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT expires_on FROM dsp_group_membership_expiry", java.sql.Date.class).toLocalDate()).isEqualTo(last);

    // Another replica reads it, by group and by member, whatever the DN's spelling.
    MembershipExpiries otherExpiries = new MembershipExpiries(props, mapper, otherReplica());
    assertThat(otherExpiries.find("def-groups", ENGINEERING.toUpperCase(), GEORGE).until()).isEqualTo(last.toString());
    assertThat(otherExpiries.ofGroup("def-groups", ENGINEERING)).hasSize(1);
    assertThat(otherExpiries.ofMember("def-groups", GEORGE)).extracting(MembershipExpiries.Expiry::groupDn).containsExactly(ENGINEERING);
    assertThat(otherExpiries.ofMember("def-groups", DAVE)).isEmpty();
    // A changed date replaces the row.
    MvcResult moved = send(json(post(java.net.URI.create("/api/groups/def-groups/members/until" + q(ENGINEERING))),
        Map.of("member", GEORGE, "until", last.plusDays(1).toString())));
    assertThat(status(moved)).isEqualTo(200);
    assertThat(otherExpiries.all()).extracting(MembershipExpiries.Expiry::until).containsExactly(last.plusDays(1).toString());
    last = last.plusDays(1);

    GroupService other = onOtherReplica(new AccessRequests(props, mapper, otherReplica()), otherExpiries);
    assertThat(other.expireMemberships(last.atTime(23, 0).toInstant(ZoneOffset.UTC))).isZero();
    Instant due = last.plusDays(1).atTime(0, 30).toInstant(ZoneOffset.UTC);
    // One replica's run removes the member; the other's finds nothing left.
    assertThat(other.expireMemberships(due) + groups.expireMemberships(due)).isEqualTo(1);
    assertThat(groups.expireMemberships(due.plusSeconds(3600)) + other.expireMemberships(due.plusSeconds(3600))).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dsp_group_membership_expiry", Long.class)).isZero();
    assertThat(auditEvents("group.membership-expired", GEORGE)).isEqualTo(1);
    assertThat(auditEvents("entry.update", "\"via\":\"group-membership-expired\"")).isEqualTo(1);
    JsonNode members = body(send(get(java.net.URI.create("/api/groups/def-groups/members" + q(ENGINEERING)))));
    assertThat(members.get("total").asInt()).isEqualTo(3);
  }

  @Test
  void aMemberThatCannotBeRemovedIsTriedAgainADayLater() throws Exception {
    setGroups(Map.of());
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    String erin = "uid=erin,ou=people," + B;
    String social = "cn=social-club,ou=groups," + B;
    // Erin is social-club's only member, and the form requires one: removing her fails.
    MvcResult set = send(json(post(java.net.URI.create("/api/groups/def-groups/members/until" + q(social))), Map.of("member", erin, "until", today.toString())));
    assertThat(status(set)).as(set.getResponse().getContentAsString()).isEqualTo(200);
    Instant due = today.plusDays(1).atTime(0, 30).toInstant(ZoneOffset.UTC);
    assertThat(groups.expireMemberships(due)).isZero();
    assertThat(expiries.find("def-groups", social, erin).lastAttempt()).isEqualTo(due.toString());
    assertThat(groups.expireMemberships(due.plusSeconds(3600))).isZero();
    assertThat(auditEvents("group.membership-expired", "\"outcome\":\"failure\"")).isEqualTo(1);
    assertThat(groups.expireMemberships(due.plus(25, ChronoUnit.HOURS))).isZero();
    assertThat(auditEvents("group.membership-expired", "\"outcome\":\"failure\"")).isEqualTo(2);
  }

  @Test
  void endDatesKeptInAFileAreImportedOnce() throws Exception {
    // What an installation that ran alone wrote, found by the first start with the database.
    Path dir = Files.createTempDirectory("dsp-expiries");
    Files.writeString(dir.resolve("group-membership-expiries.json"), """
        [{"definitionId":"def-groups","groupDn":"cn=sales,ou=groups,dc=example,dc=com","groupName":"sales",
          "member":"uid=bob,ou=people,dc=example,dc=com","until":"2031-01-31","grantedBy":"admin","grantedAt":"2026-01-05T10:00:00.000Z"}]""");
    PortalProperties moved = new PortalProperties();
    moved.setDataDir(dir.toString());
    MembershipExpiries started = new MembershipExpiries(moved, mapper, otherReplica());
    MembershipExpiries.Expiry imported = started.find("def-groups", SALES, "uid=bob,ou=people," + B);
    assertThat(imported.until()).isEqualTo("2031-01-31");
    assertThat(imported.grantedAt()).isEqualTo("2026-01-05T10:00:00Z");
    assertThat(imported.lastAttempt()).isNull();
    // The running replica sees it too, and a later start doesn't bring back what was removed since.
    assertThat(expiries.all()).hasSize(1);
    expiries.clear("def-groups", SALES, "uid=bob,ou=people," + B);
    assertThat(new MembershipExpiries(moved, mapper, otherReplica()).all()).isEmpty();
  }
}
