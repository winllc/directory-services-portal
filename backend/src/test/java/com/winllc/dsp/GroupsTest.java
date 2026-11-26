package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;

import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.winllc.dsp.groups.AccessRequests;
import com.winllc.dsp.groups.GroupAccessJobs;
import com.winllc.dsp.groups.MembershipExpiries;
import com.winllc.dsp.ldap.LdapDirectory;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.service.EntryChanged;
import com.winllc.dsp.service.LoginThrottle;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** Self-service group access: lookup, requests, owner approval, leaving and owner-managed members (demo: Groups, owners admin and carol). */
class GroupsTest extends ApiTestSupport {
  static final String ENGINEERING = "cn=engineering,ou=groups," + B;
  static final String SALES = "cn=sales,ou=groups," + B;
  static final String HR = "cn=hr,ou=groups," + B;
  static final String ADMINS = "cn=directory-admins,ou=groups," + B;
  static final String SOCIAL = "cn=social-club,ou=groups," + B;
  static final String GROUPS_OU = "ou=groups," + B;

  @Autowired AccessRequests requests;
  @Autowired GroupAccessJobs jobs;
  @Autowired ApplicationEventMulticaster multicaster;
  @Autowired MembershipExpiries expiries;
  @Autowired LdapDirectory directory;
  @Autowired com.winllc.dsp.integration.IntegrationProperties integration;
  @Autowired LoginThrottle throttle;
  @Autowired com.winllc.dsp.groups.GovernanceClient governance;

  @BeforeEach
  void noRequests() {
    requests.clear();
    expiries.clearAll();
    // Other tests lock users out on purpose (AuditLogTest: carol); a negative window drops every count.
    throttle.sweep(-1);
  }

  static Map<String, JsonNode> byName(JsonNode list) {
    Map<String, JsonNode> out = new HashMap<>();
    list.get("groups").forEach(g -> out.put(g.get("name").asString(), g));
    return out;
  }

  String ask(Client c, String dn, String reason) throws Exception {
    MvcResult r = c.post("/api/groups/def-groups/requests" + q(dn), reason == null ? Map.of() : Map.of("reason", reason));
    assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(201);
    return body(r).get("id").asString();
  }

  boolean member(Client c, String dn) throws Exception {
    return body(c.get("/api/groups/def-groups/group" + q(dn))).get("group").get("member").asBoolean();
  }

  @Test
  void looksGroupsUpWithTheUsersRelationToThem() throws Exception {
    Client alice = login("alice");
    assertThat(body(alice.get("/api/groups/directories")).get(0).get("id").asString()).isEqualTo("def-groups");
    Map<String, JsonNode> all = byName(body(alice.get("/api/groups")));
    assertThat(all).containsKeys("engineering", "sales", "hr", "directory-admins");
    assertThat(all.get("engineering").get("member").asBoolean()).isTrue();
    assertThat(all.get("sales").get("member").asBoolean()).isFalse();
    assertThat(all.get("sales").get("requestable").asBoolean()).isTrue();
    // The request filter leaves the administrators' group out.
    assertThat(all.get("directory-admins").get("requestable").asBoolean()).isFalse();
    assertThat(all.get("engineering").get("canManage").asBoolean()).isFalse();

    assertThat(byName(body(alice.get("/api/groups?q=sal")))).containsOnlyKeys("sales");
    assertThat(byName(body(alice.get("/api/groups/mine")))).containsOnlyKeys("engineering");

    JsonNode detail = body(alice.get("/api/groups/def-groups/group" + q(ENGINEERING)));
    assertThat(detail.get("memberCount").asInt()).isEqualTo(3);
    List<String> owners = new ArrayList<>();
    detail.get("owners").forEach(o -> owners.add(o.get("name").asString()));
    assertThat(owners).containsExactlyInAnyOrder("Ada Admin", "Carol Chen");
    assertThat(detail.get("requireReason").asBoolean()).isTrue();

    // Carol owns engineering; members and requests are for owners only.
    Client carol = login("carol");
    assertThat(byName(body(carol.get("/api/groups"))).get("engineering").get("canManage").asBoolean()).isTrue();
    assertThat(status(alice.get("/api/groups/def-groups/members" + q(ENGINEERING)))).isEqualTo(403);
    assertThat(status(alice.get("/api/groups/def-groups/group" + q("cn=nope,ou=groups," + B)))).isEqualTo(404);
    assertThat(status(alice.get("/api/groups/def-staff/group" + q(ALICE)))).isEqualTo(404);
  }

  @Test
  void anApprovalChangesTheGroupOnlyForARequestItClosed() throws Exception {
    Client dave = login("dave");
    String id = ask(dave, ENGINEERING, "On-call rotation");
    // The requester withdraws while the directory is adding them.
    List<String> cancel = new java.util.concurrent.CopyOnWriteArrayList<>();
    embedded.intercept(new com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor() {
      @Override
      public void processModifyRequest(com.unboundid.ldap.listener.interceptor.InMemoryInterceptedModifyRequest request) {
        try {
          requests.close(id, AccessRequests.CANCELLED, "dave", "Dave", null);
          cancel.add("cancelled");
        } catch (RuntimeException e) {
          cancel.add(e.getMessage());
        }
      }
    });
    Client carol = login("carol");
    MvcResult approved = carol.post("/api/groups/requests/" + id + "/approve", Map.of());
    assertThat(status(approved)).as(approved.getResponse().getContentAsString()).isEqualTo(200);
    assertThat(cancel).containsExactly("This request was already approved");
    assertThat(requests.find(id).status()).isEqualTo(AccessRequests.APPROVED);
    embedded.intercept(null);
    assertThat(member(dave, ENGINEERING)).isTrue();
  }

  @Test
  void aRequestTheDirectoryCouldNotCarryOutStaysOpen() throws Exception {
    Client dave = login("dave");
    String id = ask(dave, ENGINEERING, "On-call rotation");
    embedded.intercept(new com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor() {
      @Override
      public void processModifyRequest(com.unboundid.ldap.listener.interceptor.InMemoryInterceptedModifyRequest request)
          throws com.unboundid.ldap.sdk.LDAPException {
        throw new com.unboundid.ldap.sdk.LDAPException(com.unboundid.ldap.sdk.ResultCode.CONSTRAINT_VIOLATION, "refused");
      }
    });
    Client carol = login("carol");
    assertThat(status(carol.post("/api/groups/requests/" + id + "/approve", Map.of()))).isEqualTo(400);
    assertThat(requests.find(id).pending()).isTrue();
    embedded.intercept(null);
    assertThat(status(carol.post("/api/groups/requests/" + id + "/approve", Map.of()))).isEqualTo(200);
    assertThat(member(dave, ENGINEERING)).isTrue();
  }

  @Test
  void ownersApproveAndRejectRequests() throws Exception {
    Client dave = login("dave");
    // A reason is required here; members, the administrators' group and repeats are refused.
    assertThat(status(dave.post("/api/groups/def-groups/requests" + q(ENGINEERING), Map.of()))).isEqualTo(400);
    String id = ask(dave, ENGINEERING, "On-call rotation");
    assertThat(status(dave.post("/api/groups/def-groups/requests" + q(ENGINEERING), Map.of("reason", "again")))).isEqualTo(409);
    assertThat(status(dave.post("/api/groups/def-groups/requests" + q(ADMINS), Map.of("reason", "please")))).isEqualTo(403);
    Client alice = login("alice");
    assertThat(status(alice.post("/api/groups/def-groups/requests" + q(ENGINEERING), Map.of("reason", "x")))).isEqualTo(409);

    JsonNode mine = body(dave.get("/api/groups/requests"));
    assertThat(mine.get(0).get("request").get("status").asString()).isEqualTo("pending");
    assertThat(body(dave.get("/api/groups/def-groups/group" + q(ENGINEERING))).get("group").get("pendingRequestId").asString()).isEqualTo(id);

    // Carol owns engineering (and has no write grant on Groups); bob owns nothing.
    Client carol = login("carol");
    Client bob = login("bob");
    assertThat(body(carol.get("/api/groups/requests/summary")).get("toReview").asInt()).isEqualTo(1);
    assertThat(body(bob.get("/api/groups/requests/summary")).get("toReview").asInt()).isZero();
    assertThat(body(bob.get("/api/groups/requests?scope=review"))).isEmpty();
    assertThat(status(bob.post("/api/groups/requests/" + id + "/approve", Map.of()))).isEqualTo(404);
    assertThat(status(dave.post("/api/groups/requests/" + id + "/approve", Map.of()))).isEqualTo(403);
    JsonNode review = body(carol.get("/api/groups/requests?scope=review"));
    assertThat(review.get(0).get("request").get("reason").asString()).isEqualTo("On-call rotation");
    assertThat(review.get(0).get("canDecide").asBoolean()).isTrue();

    MvcResult approved = carol.post("/api/groups/requests/" + id + "/approve", Map.of("note", "Welcome"));
    assertThat(status(approved)).as(approved.getResponse().getContentAsString()).isEqualTo(200);
    assertThat(body(approved).get("status").asString()).isEqualTo("approved");
    assertThat(member(dave, ENGINEERING)).isTrue();
    assertThat(status(carol.post("/api/groups/requests/" + id + "/reject", Map.of()))).isEqualTo(409);

    // The membership change is audited as carol's, saying why she could make it.
    Client admin = login("admin");
    JsonNode update = body(admin.get("/api/admin/audit?action=entry.update")).get("events").get(0);
    assertThat(update.get("actor").get("id").asString()).isEqualTo("carol");
    assertThat(update.get("details").get("via").asString()).isEqualTo("group-access-request");
    JsonNode decision = body(admin.get("/api/admin/audit?action=group.access-approved")).get("events").get(0);
    assertThat(decision.get("target").get("id").asString()).isEqualTo(ENGINEERING);

    // A rejection leaves the group as it is, with the owner's note for the requester.
    Client erin = login("erin");
    String erins = ask(erin, ENGINEERING, "Curious");
    assertThat(body(carol.post("/api/groups/requests/" + erins + "/reject", Map.of("note", "Not for HR"))).get("status").asString())
        .isEqualTo("rejected");
    JsonNode closed = body(erin.get("/api/groups/requests")).get(0).get("request");
    assertThat(closed.get("note").asString()).isEqualTo("Not for HR");
    assertThat(closed.get("decidedByName").asString()).isEqualTo("Carol Chen");
    assertThat(member(erin, ENGINEERING)).isFalse();
    assertThat(body(carol.get("/api/groups/requests?scope=decided"))).hasSize(2);

    // Administrators decide any group's requests.
    String sales = ask(dave, SALES, "Demo support");
    assertThat(status(admin.post("/api/groups/requests/" + sales + "/approve", Map.of()))).isEqualTo(200);
    assertThat(member(dave, SALES)).isTrue();
  }

  @Test
  void requestersCancelAndMembersLeave() throws Exception {
    Client alice = login("alice");
    String id = ask(alice, HR, "Benefits project");
    assertThat(status(login("bob").post("/api/groups/requests/" + id + "/cancel", null))).isEqualTo(404);
    assertThat(body(alice.post("/api/groups/requests/" + id + "/cancel", null)).get("status").asString()).isEqualTo("cancelled");
    assertThat(status(login("admin").post("/api/groups/requests/" + id + "/approve", Map.of()))).isEqualTo(409);
    assertThat(body(login("admin").get("/api/groups/requests/summary")).get("toReview").asInt()).isZero();

    assertThat(status(alice.post("/api/groups/def-groups/leave" + q(ENGINEERING), null))).isEqualTo(200);
    assertThat(member(alice, ENGINEERING)).isFalse();
    assertThat(status(alice.post("/api/groups/def-groups/leave" + q(ENGINEERING), null))).isEqualTo(409);
    JsonNode update = body(login("admin").get("/api/admin/audit?action=entry.update")).get("events").get(0);
    assertThat(update.get("details").get("via").asString()).isEqualTo("group-leave");

    // Without allowLeave, members ask the owners instead.
    setGroups(Map.of("enabled", true, "requireReason", false, "allowLeave", false));
    assertThat(status(login("carol").post("/api/groups/def-groups/leave" + q(ENGINEERING), null))).isEqualTo(403);
    // ... and a reason is optional now.
    ask(login("bob"), HR, null);
  }

  @Test
  void ownersManageTheirGroupsMembers() throws Exception {
    Client carol = login("carol");
    JsonNode page = body(carol.get("/api/groups/def-groups/members" + q(ENGINEERING)));
    assertThat(page.get("total").asInt()).isEqualTo(3);
    List<String> names = new ArrayList<>();
    page.get("items").forEach(p -> names.add(p.get("name").asString()));
    assertThat(names).contains("Alice Anderson", "Fatima Farouk");
    assertThat(body(carol.get("/api/groups/def-groups/members" + q(ENGINEERING) + "&q=fatima")).get("total").asInt()).isEqualTo(1);

    JsonNode people = body(carol.get("/api/groups/def-groups/people" + q(ENGINEERING) + "&q=george"));
    assertThat(people.get(0).get("value").asString()).isEqualTo("uid=george,ou=people," + B);

    MvcResult changed = carol.post("/api/groups/def-groups/members" + q(ENGINEERING),
        Map.of("add", List.of("uid=george,ou=people," + B), "remove", List.of("uid=fatima,ou=people," + B)));
    assertThat(status(changed)).as(changed.getResponse().getContentAsString()).isEqualTo(200);
    assertThat(member(login("george"), ENGINEERING)).isTrue();
    assertThat(member(login("fatima"), ENGINEERING)).isFalse();
    JsonNode update = body(login("admin").get("/api/admin/audit?action=entry.update")).get("events").get(0);
    assertThat(update.get("details").get("via").asString()).isEqualTo("group-owner");

    // Not carol's group, and bob owns none.
    assertThat(status(carol.post("/api/groups/def-groups/members" + q(SALES), Map.of("add", List.of(ALICE), "remove", List.of())))).isEqualTo(403);
    assertThat(status(login("bob").get("/api/groups/def-groups/people" + q(ENGINEERING)))).isEqualTo(403);
  }

  /**
   * Governed mode against a stand-in governance console: requests go there on the requester's
   * behalf, owners can't decide them, groups governance closes are closed to requests, and the
   * console's state is mirrored into "my requests".
   */
  @Test
  void governedRequestsGoToTheGovernanceConsole() throws Exception {
    List<String> posted = new java.util.concurrent.CopyOnWriteArrayList<>();
    List<String> auth = new java.util.concurrent.CopyOnWriteArrayList<>();
    java.util.concurrent.atomic.AtomicReference<String> remoteState = new java.util.concurrent.atomic.AtomicReference<>("pending_approval");
    com.sun.net.httpserver.HttpServer console = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    console.createContext("/api/v1/governance/", ex -> {
      auth.add(ex.getRequestHeaders().getFirst("Authorization"));
      String path = ex.getRequestURI().getPath();
      String query = java.net.URLDecoder.decode(String.valueOf(ex.getRequestURI().getRawQuery()), java.nio.charset.StandardCharsets.UTF_8);
      String answer;
      if (path.endsWith("/entitlements/lookup")) {
        String dn = query.toLowerCase(java.util.Locale.ROOT);
        answer = dn.contains("cn=sales") ? "{\"id\":\"e-sales\",\"known\":true,\"requestable\":true,\"managed_by\":\"grant\"}"
            : dn.contains("cn=engineering") ? "{\"id\":\"e-eng\",\"known\":true,\"requestable\":true,\"managed_by\":\"grant\"}"
            : dn.contains("cn=hr") ? "{\"id\":\"e-hr\",\"known\":true,\"requestable\":false,\"managed_by\":\"rule\"}"
            : "{\"id\":null,\"known\":false,\"requestable\":false,\"managed_by\":\"\"}";
      } else if (path.equals("/api/v1/governance/requests") && "POST".equals(ex.getRequestMethod())) {
        posted.add(new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        answer = "{\"id\":\"g-1\",\"state\":\"pending_approval\"}";
      } else if (path.equals("/api/v1/governance/requests/g-1/state")) {
        answer = "{\"id\":\"g-1\",\"state\":\"" + remoteState.get() + "\",\"detail\":\"\"}";
      } else {
        answer = "{\"error\":\"not_found\",\"message\":\"no such path\"}";
        byte[] b = answer.getBytes();
        ex.sendResponseHeaders(404, b.length);
        ex.getResponseBody().write(b);
        ex.close();
        return;
      }
      byte[] b = answer.getBytes();
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, b.length);
      ex.getResponseBody().write(b);
      ex.close();
    });
    console.start();
    var props = governance.properties();
    try {
      props.setUrl("http://127.0.0.1:" + console.getAddress().getPort());
      props.setToken("sdt_portal-token");
      props.setPortalUrl("https://governance.example");
      props.setSystems("*=sys-1");
      governance.forget();

      Client dave = login("dave");
      Map<String, JsonNode> groups = byName(body(dave.get("/api/groups")));
      assertThat(groups.get("sales").get("requestable").asBoolean()).isTrue();
      // Computed by a rule in governance: closed to requests here.
      assertThat(groups.get("hr").get("requestable").asBoolean()).isFalse();

      String id = ask(dave, SALES, "Quarterly forecast");
      assertThat(posted).hasSize(1);
      JsonNode sent = mapper.readTree(posted.get(0));
      assertThat(sent.get("on_behalf_of").asString()).isEqualTo("dave");
      assertThat(sent.get("source").asString()).isEqualTo("dsp");
      assertThat(sent.get("items").get(0).get("entitlement_id").asString()).isEqualTo("e-sales");
      assertThat(sent.get("items").get(0).has("ends_at")).isFalse();
      assertThat(auth).allMatch("Bearer sdt_portal-token"::equals);
      assertThat(requests.find(id).governanceId()).isEqualTo("g-1");
      assertThat(status(dave.post("/api/groups/def-groups/requests" + q(HR), Map.of("reason", "x")))).isEqualTo(403);

      // Owners and editors don't decide it, and don't see it to review.
      Client admin = login("admin");
      MvcResult decided = admin.post("/api/groups/requests/" + id + "/approve", Map.of());
      assertThat(status(decided)).isEqualTo(409);
      assertThat(decided.getResponse().getContentAsString()).contains("governance portal");
      assertThat(body(admin.get("/api/groups/requests?scope=review")).valueStream().map(v -> v.get("request").get("id").asString()))
          .doesNotContain(id);
      assertThat(status(dave.post("/api/groups/requests/" + id + "/cancel", Map.of()))).isEqualTo(409);

      // Mirrored when the requester lists their requests.
      JsonNode mine = body(dave.get("/api/groups/requests?scope=mine")).get(0);
      assertThat(mine.get("governanceUrl").asString()).isEqualTo("https://governance.example/my/requests/g-1");
      assertThat(mine.get("request").get("governanceState").asString()).isEqualTo("pending_approval");
      remoteState.set("completed");
      mine = body(dave.get("/api/groups/requests?scope=mine")).get(0);
      assertThat(mine.get("request").get("status").asString()).isEqualTo(AccessRequests.APPROVED);
      assertThat(mine.get("request").get("decidedBy").asString()).isEqualTo("governance");

      // An end date asked for goes to the console as the item's end: the start of the next day.
      LocalDate last = LocalDate.now(ZoneOffset.UTC).plusDays(10);
      assertThat(status(dave.post("/api/groups/def-groups/requests" + q(ENGINEERING), Map.of("reason", "On call", "until", last.toString()))))
          .isEqualTo(201);
      assertThat(mapper.readTree(posted.get(1)).get("items").get(0).get("ends_at").asString()).isEqualTo(last.plusDays(1) + "T00:00:00Z");
    } finally {
      props.setUrl(null);
      props.setToken(null);
      props.setPortalUrl(null);
      props.setSystems(null);
      governance.forget();
      console.stop(0);
    }
  }

  @Test
  void ownersManageOwnersWhenTheDirectoryAllowsIt() throws Exception {
    String george = "uid=george,ou=people," + B;
    String carolDn = "uid=carol,ou=people," + B;
    String adminDn = "uid=admin,ou=people," + B;
    Client carol = login("carol");
    // Off unless the definition says so (as stored before there was the setting).
    setGroups(Map.of("enabled", true, "requireReason", false, "allowLeave", true));
    assertThat(body(carol.get("/api/groups/def-groups/group" + q(ENGINEERING))).get("canManageOwners").asBoolean()).isFalse();
    assertThat(status(carol.post("/api/groups/def-groups/owners" + q(ENGINEERING), Map.of("add", List.of(george), "remove", List.of())))).isEqualTo(403);

    setGroups(groups(Map.of("ownersManageOwners", true)));
    assertThat(body(carol.get("/api/groups/def-groups/group" + q(ENGINEERING))).get("canManageOwners").asBoolean()).isTrue();
    assertThat(body(login("alice").get("/api/groups/def-groups/group" + q(ENGINEERING))).get("canManageOwners").asBoolean()).isFalse();
    MvcResult added = carol.post("/api/groups/def-groups/owners" + q(ENGINEERING), Map.of("add", List.of(george), "remove", List.of()));
    assertThat(status(added)).as(added.getResponse().getContentAsString()).isEqualTo(200);
    assertThat(body(added).get("added").get(0).asString()).isEqualTo(george);
    // George owns the group now: he manages it too.
    assertThat(body(login("george").get("/api/groups/def-groups/group" + q(ENGINEERING))).get("group").get("canManage").asBoolean()).isTrue();
    Client admin = login("admin");
    JsonNode update = body(admin.get("/api/admin/audit?action=entry.update")).get("events").get(0);
    assertThat(update.get("actor").get("id").asString()).isEqualTo("carol");
    assertThat(update.get("details").get("via").asString()).isEqualTo("group-owners");
    assertThat(update.get("details").get("owner.new").asString()).isEqualTo(george);

    // Only people of the directory become owners; members, strangers and other groups' owners change nothing.
    assertThat(status(carol.post("/api/groups/def-groups/owners" + q(ENGINEERING), Map.of("add", List.of(SALES), "remove", List.of())))).isEqualTo(400);
    assertThat(status(carol.post("/api/groups/def-groups/owners" + q(ENGINEERING), Map.of("add", List.of("not a dn"), "remove", List.of())))).isEqualTo(400);
    assertThat(status(carol.post("/api/groups/def-groups/owners" + q(ENGINEERING), Map.of("add", List.of(), "remove", List.of())))).isEqualTo(400);
    List<String> many = new ArrayList<>();
    for (int i = 0; i < 21; i++) many.add("uid=u" + i + ",ou=people," + B);
    assertThat(status(carol.post("/api/groups/def-groups/owners" + q(ENGINEERING), Map.of("add", many, "remove", List.of())))).isEqualTo(400);
    assertThat(status(login("alice").post("/api/groups/def-groups/owners" + q(ENGINEERING), Map.of("add", List.of(ALICE), "remove", List.of())))).isEqualTo(403);
    assertThat(status(login("bob").post("/api/groups/def-groups/owners" + q(ENGINEERING), Map.of("add", List.of(), "remove", List.of(carolDn))))).isEqualTo(403);
    assertThat(status(carol.post("/api/groups/def-groups/owners" + q(SALES), Map.of("add", List.of(carolDn), "remove", List.of())))).isEqualTo(403);

    // Any owner value can go, but never the last one.
    MvcResult removed = carol.post("/api/groups/def-groups/owners" + q(ENGINEERING), Map.of("add", List.of(), "remove", List.of(adminDn, george.toUpperCase())));
    assertThat(status(removed)).as(removed.getResponse().getContentAsString()).isEqualTo(200);
    assertThat(body(removed).get("removed")).hasSize(2);
    MvcResult last = carol.post("/api/groups/def-groups/owners" + q(ENGINEERING), Map.of("add", List.of(), "remove", List.of(carolDn)));
    assertThat(status(last)).isEqualTo(400);
    assertThat(body(last).get("error").asString()).contains("at least one owner");
    // ... unless another one replaces them in the same change.
    assertThat(status(carol.post("/api/groups/def-groups/owners" + q(ENGINEERING), Map.of("add", List.of(george), "remove", List.of(carolDn))))).isEqualTo(200);
    assertThat(status(carol.post("/api/groups/def-groups/owners" + q(ENGINEERING), Map.of("add", List.of(carolDn), "remove", List.of())))).isEqualTo(403);

    // Those who may edit the definition change owners as editors (no via).
    assertThat(status(admin.post("/api/groups/def-groups/owners" + q(SALES), Map.of("add", List.of(carolDn), "remove", List.of())))).isEqualTo(200);
    assertThat(body(admin.get("/api/admin/audit?action=entry.update")).get("events").get(0).get("details").has("via")).isFalse();
  }

  @Test
  void openRequestsExpireAfterTheDefinitionsDays() throws Exception {
    Client dave = login("dave");
    Client carol = login("carol");
    // Without the setting (as stored before there was one) requests wait for good.
    setGroups(Map.of("enabled", true, "requireReason", false, "allowLeave", true));
    String waiting = ask(dave, ENGINEERING, "On-call rotation");
    Instant asked = Instant.parse(requests.find(waiting).createdAt());
    assertThat(jobs.expireRequests(asked.plus(400, ChronoUnit.DAYS))).isZero();
    assertThat(requests.find(waiting).pending()).isTrue();

    setGroups(groups(Map.of("requestExpiryDays", 14)));
    String answered = ask(login("erin"), ENGINEERING, "Curious");
    assertThat(status(carol.post("/api/groups/requests/" + answered + "/reject", Map.of()))).isEqualTo(200);
    assertThat(jobs.expireRequests(asked.plus(13, ChronoUnit.DAYS))).isZero();
    // The job pauses with the portal's other scheduled work while the license is read-only.
    TestLicensing.use(TestLicenses.license().expired().sign());
    assertThat(jobs.expireRequests(asked.plus(15, ChronoUnit.DAYS))).isZero();
    TestLicensing.use(TestLicenses.full());
    Instant due = asked.plus(14, ChronoUnit.DAYS).plusSeconds(60);
    assertThat(jobs.expireRequests(due)).isEqualTo(1);
    // Once: a second run (another replica's, a later one) finds nothing to close.
    assertThat(jobs.expireRequests(due.plusSeconds(60))).isZero();

    JsonNode mine = body(dave.get("/api/groups/requests")).get(0).get("request");
    assertThat(mine.get("status").asString()).isEqualTo("expired");
    assertThat(mine.get("expiredAfterDays").asInt()).isEqualTo(14);
    assertThat(mine.get("decidedAt").asString()).isEqualTo(due.truncatedTo(ChronoUnit.MILLIS).toString());
    assertThat(mine.has("decidedBy")).isFalse();
    // It is nobody's to decide any more, and the requester may ask again.
    assertThat(body(carol.get("/api/groups/requests?scope=review"))).isEmpty();
    assertThat(status(carol.post("/api/groups/requests/" + waiting + "/approve", Map.of()))).isEqualTo(409);
    assertThat(member(dave, ENGINEERING)).isFalse();
    assertThat(body(dave.get("/api/groups/def-groups/group" + q(ENGINEERING))).get("group").get("pendingRequestId").isNull()).isTrue();
    assertThat(body(carol.get("/api/groups/requests?scope=decided"))).hasSize(1);
    assertThat(requests.find(answered).status()).isEqualTo(AccessRequests.REJECTED);

    List<JsonNode> events = events("group.access-expired", "request", waiting);
    assertThat(events).hasSize(1);
    assertThat(events.get(0).get("actor").get("type").asString()).isEqualTo("system");
    assertThat(events.get(0).get("target").get("id").asString()).isEqualTo(ENGINEERING);
    assertThat(events.get(0).get("details").get("request").asString()).isEqualTo(waiting);
    assertThat(events.get(0).get("details").get("afterDays").asInt()).isEqualTo(14);
    ask(dave, ENGINEERING, "Still on call");

    // The days are capped: closed requests are kept for a year.
    Map<String, Object> def = definitionInput();
    def.put("groups", groups(Map.of("requestExpiryDays", 366)));
    assertThat(status(login("admin").put("/api/admin/definitions/def-groups", def))).isEqualTo(400);
    def.put("groups", groups(Map.of("requestExpiryDays", -1)));
    assertThat(status(login("admin").put("/api/admin/definitions/def-groups", def))).isEqualTo(400);
  }

  /** The entry changes published while {@code action} runs: what webhooks are sent from. */
  List<EntryChanged> changesDuring(java.util.concurrent.Callable<?> action) throws Exception {
    List<EntryChanged> changes = new java.util.concurrent.CopyOnWriteArrayList<>();
    ApplicationListener<ApplicationEvent> listener = e -> {
      if (e instanceof PayloadApplicationEvent<?> p && p.getPayload() instanceof EntryChanged c) changes.add(c);
    };
    multicaster.addApplicationListener(listener);
    try {
      action.call();
    } finally {
      multicaster.removeApplicationListener(listener);
    }
    return changes;
  }

  @Test
  void openGroupsAreJoinedWithoutApproval() throws Exception {
    String daveDn = "uid=dave,ou=people," + B;
    Client dave = login("dave");
    Map<String, JsonNode> all = byName(body(dave.get("/api/groups")));
    assertThat(all.get("social-club").get("open").asBoolean()).isTrue();
    assertThat(all.get("sales").get("open").asBoolean()).isFalse();
    assertThat(body(dave.get("/api/groups/def-groups/group" + q(SOCIAL))).get("group").get("open").asBoolean()).isTrue();

    // Other groups still need their owners, and an open group needs no request.
    assertThat(status(dave.post("/api/groups/def-groups/join" + q(SALES), null))).isEqualTo(403);
    assertThat(member(dave, SALES)).isFalse();
    assertThat(status(dave.post("/api/groups/def-groups/requests" + q(SOCIAL), Map.of("reason", "Lunch")))).isEqualTo(409);
    assertThat(status(dave.post("/api/groups/def-groups/join" + q("cn=nope,ou=groups," + B), null))).isEqualTo(404);

    MvcResult[] joined = new MvcResult[1];
    List<EntryChanged> changes = changesDuring(() -> joined[0] = dave.post("/api/groups/def-groups/join" + q(SOCIAL), null));
    assertThat(status(joined[0])).as(joined[0].getResponse().getContentAsString()).isEqualTo(200);
    assertThat(body(joined[0]).get("added").get(0).asString()).isEqualTo(daveDn);
    assertThat(member(dave, SOCIAL)).isTrue();
    assertThat(status(dave.post("/api/groups/def-groups/join" + q(SOCIAL), null))).isEqualTo(409);
    // Audited as dave's own change, and passed on like every membership change (the webhook).
    JsonNode update = body(login("admin").get("/api/admin/audit?action=entry.update")).get("events").get(0);
    assertThat(update.get("actor").get("id").asString()).isEqualTo("dave");
    assertThat(update.get("details").get("via").asString()).isEqualTo("group-join");
    assertThat(update.get("details").get("member.new").asString()).isEqualTo(daveDn);
    assertThat(changes).hasSize(1);
    assertThat(changes.get(0).dn()).isEqualTo(SOCIAL);
    assertThat(changes.get(0).changes().get(0).attribute()).isEqualTo("member");
    assertThat(changes.get(0).changes().get(0).after()).containsExactly(daveDn);

    // Only groups that take requests at all are open, whatever the filter says.
    setGroups(groups(Map.of("requestFilter", "(!(cn=directory-admins))", "openFilter", "(|(cn=directory-admins)(cn=sales))")));
    assertThat(byName(body(dave.get("/api/groups"))).get("directory-admins").get("open").asBoolean()).isFalse();
    assertThat(status(dave.post("/api/groups/def-groups/join" + q(ADMINS), null))).isEqualTo(403);
    assertThat(byName(body(dave.get("/api/groups"))).get("social-club").get("open").asBoolean()).isFalse();

    // A request made before the group was opened is withdrawn by joining.
    setGroups(groups(Map.of()));
    Client george = login("george");
    String asked = ask(george, SALES, null);
    assertThat(status(george.post("/api/groups/def-groups/join" + q(SALES), null))).isEqualTo(403);
    setGroups(groups(Map.of("openFilter", "(cn=sales)")));
    assertThat(status(george.post("/api/groups/def-groups/join" + q(SALES), null))).isEqualTo(200);
    assertThat(requests.find(asked).status()).isEqualTo(AccessRequests.CANCELLED);
    assertThat(member(george, SALES)).isTrue();

    Map<String, Object> def = definitionInput();
    def.put("groups", groups(Map.of("openFilter", "(oops")));
    assertThat(status(login("admin").put("/api/admin/definitions/def-groups", def))).isEqualTo(400);
  }

  /** The audit events of an action whose {@code details[key]} is {@code value}, newest first (the trail spans the class's tests). */
  List<JsonNode> events(String action, String key, String value) throws Exception {
    List<JsonNode> out = new ArrayList<>();
    for (JsonNode e : body(login("admin").get("/api/admin/audit?action=" + action)).get("events")) {
      if (e.get("details").has(key) && e.get("details").get(key).asString().equals(value)) out.add(e);
    }
    return out;
  }

  /** The end date the member list shows for {@code value} (null: none), as the group's owners see it. */
  String memberUntil(Client owner, String group, String value) throws Exception {
    for (JsonNode m : body(owner.get("/api/groups/def-groups/members" + q(group))).get("items")) {
      if (m.get("value").asString().equals(value)) return m.get("until").isNull() ? null : m.get("until").asString();
    }
    throw new AssertionError(value + " is not listed in " + group);
  }

  static Instant at(LocalDate day, int hour) {
    return day.atTime(hour, 30).toInstant(ZoneOffset.UTC);
  }

  @Test
  void aMembershipAskedForUntilADateEndsWhenItHasPassed() throws Exception {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    String daveDn = "uid=dave,ou=people," + B;
    Client dave = login("dave");
    Client carol = login("carol");
    // A day from today on, as a date, and not absurdly far away.
    for (String bad : List.of(today.minusDays(1).toString(), "next week", today.plusDays(3651).toString())) {
      MvcResult r = dave.post("/api/groups/def-groups/requests" + q(ENGINEERING), Map.of("reason", "On call", "until", bad));
      assertThat(status(r)).as(bad).isEqualTo(400);
      assertThat(body(r).get("details").has("until")).isTrue();
    }
    MvcResult asked = dave.post("/api/groups/def-groups/requests" + q(ENGINEERING), Map.of("reason", "On call", "until", today.plusDays(30).toString()));
    assertThat(status(asked)).isEqualTo(201);
    String id = body(asked).get("id").asString();
    assertThat(body(carol.get("/api/groups/requests?scope=review")).get(0).get("request").get("until").asString()).isEqualTo(today.plusDays(30).toString());

    // The approver may shorten the access, not extend it.
    MvcResult longer = carol.post("/api/groups/requests/" + id + "/approve", Map.of("until", today.plusDays(31).toString()));
    assertThat(status(longer)).isEqualTo(400);
    assertThat(body(longer).get("details").get("until").asString()).contains(today.plusDays(30).toString());
    assertThat(requests.find(id).pending()).isTrue();
    LocalDate last = today.plusDays(10);
    MvcResult approved = carol.post("/api/groups/requests/" + id + "/approve", Map.of("until", last.toString()));
    assertThat(status(approved)).as(approved.getResponse().getContentAsString()).isEqualTo(200);
    assertThat(body(approved).get("grantedUntil").asString()).isEqualTo(last.toString());
    assertThat(body(approved).get("until").asString()).isEqualTo(today.plusDays(30).toString());

    // Owners see the end date in the member list, the member under My groups and on the group's page.
    assertThat(memberUntil(carol, ENGINEERING, daveDn)).isEqualTo(last.toString());
    assertThat(memberUntil(carol, ENGINEERING, ALICE)).isNull();
    assertThat(byName(body(dave.get("/api/groups/mine"))).get("engineering").get("memberUntil").asString()).isEqualTo(last.toString());
    assertThat(body(dave.get("/api/groups/def-groups/group" + q(ENGINEERING))).get("group").get("memberUntil").asString()).isEqualTo(last.toString());
    assertThat(byName(body(login("alice").get("/api/groups/mine"))).get("engineering").get("memberUntil").isNull()).isTrue();
    Client admin = login("admin");
    assertThat(body(admin.get("/api/admin/audit?action=group.access-approved")).get("events").get(0).get("details").get("until").asString())
        .isEqualTo(last.toString());

    // The membership lasts through its last day (UTC); the job pauses while the license is read-only.
    assertThat(jobs.expireMemberships(at(last, 23))).isZero();
    assertThat(member(dave, ENGINEERING)).isTrue();
    TestLicensing.use(TestLicenses.license().expired().sign());
    assertThat(jobs.expireMemberships(at(last.plusDays(1), 0))).isZero();
    TestLicensing.use(TestLicenses.full());
    int[] removed = new int[1];
    List<EntryChanged> changes = changesDuring(() -> removed[0] = jobs.expireMemberships(at(last.plusDays(1), 0)));
    assertThat(removed[0]).isEqualTo(1);
    assertThat(member(dave, ENGINEERING)).isFalse();
    assertThat(member(login("alice"), ENGINEERING)).isTrue();
    // Once: a later run (or another replica's) has nothing left to do.
    assertThat(jobs.expireMemberships(at(last.plusDays(1), 1))).isZero();
    assertThat(expiries.all()).isEmpty();

    // The usual entry.update by the system, a group.membership-expired event, and the webhook's change.
    JsonNode update = body(admin.get("/api/admin/audit?action=entry.update")).get("events").get(0);
    assertThat(update.get("actor").get("type").asString()).isEqualTo("system");
    assertThat(update.get("details").get("via").asString()).isEqualTo("group-membership-expired");
    assertThat(update.get("details").get("member.old").asString()).isEqualTo(daveDn);
    List<JsonNode> expired = events("group.membership-expired", "member", daveDn);
    assertThat(expired).hasSize(1);
    assertThat(expired.get(0).get("outcome").asString()).isEqualTo("success");
    assertThat(expired.get(0).get("target").get("id").asString()).isEqualTo(ENGINEERING);
    assertThat(expired.get(0).get("details").get("member").asString()).isEqualTo(daveDn);
    assertThat(expired.get(0).get("details").get("until").asString()).isEqualTo(last.toString());
    assertThat(changes).hasSize(1);
    assertThat(changes.get(0).dn()).isEqualTo(ENGINEERING);
    assertThat(changes.get(0).changes().get(0).before()).containsExactly(daveDn);
    assertThat(changes.get(0).actor().username()).isNull();
  }

  @Test
  void ownersSetChangeAndClearEndDates() throws Exception {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    String george = "uid=george,ou=people," + B;
    Client carol = login("carol");
    String members = "/api/groups/def-groups/members" + q(ENGINEERING);
    String until = "/api/groups/def-groups/members/until" + q(ENGINEERING);

    // Added with an end date.
    MvcResult added = carol.post(members, Map.of("add", List.of(george), "remove", List.of(), "until", today.plusDays(5).toString()));
    assertThat(status(added)).as(added.getResponse().getContentAsString()).isEqualTo(200);
    assertThat(memberUntil(carol, ENGINEERING, george)).isEqualTo(today.plusDays(5).toString());
    assertThat(status(carol.post(members, Map.of("add", List.of("uid=erin,ou=people," + B), "remove", List.of(), "until", "soon")))).isEqualTo(400);
    assertThat(member(login("erin"), ENGINEERING)).isFalse();

    // Changed, set for a member who had none, and cleared; only by those who manage the group.
    assertThat(status(carol.post(until, Map.of("member", george, "until", today.plusDays(7).toString())))).isEqualTo(200);
    assertThat(memberUntil(carol, ENGINEERING, george)).isEqualTo(today.plusDays(7).toString());
    JsonNode event = body(login("admin").get("/api/admin/audit?action=group.membership-end-date")).get("events").get(0);
    assertThat(event.get("actor").get("id").asString()).isEqualTo("carol");
    assertThat(event.get("details").get("member").asString()).isEqualTo(george);
    assertThat(event.get("details").get("until.old").asString()).isEqualTo(today.plusDays(5).toString());
    assertThat(event.get("details").get("until.new").asString()).isEqualTo(today.plusDays(7).toString());
    assertThat(status(carol.post(until, Map.of("member", ALICE, "until", today.plusDays(20).toString())))).isEqualTo(200);
    assertThat(memberUntil(carol, ENGINEERING, ALICE)).isEqualTo(today.plusDays(20).toString());
    assertThat(status(carol.post(until, Map.of("member", george)))).isEqualTo(200);
    assertThat(memberUntil(carol, ENGINEERING, george)).isNull();
    assertThat(status(login("alice").post(until, Map.of("member", ALICE)))).isEqualTo(403);
    assertThat(status(login("bob").post(until, Map.of("member", ALICE, "until", today.plusDays(900).toString())))).isEqualTo(403);
    assertThat(status(carol.post("/api/groups/def-groups/members/until" + q(SALES), Map.of("member", "uid=bob,ou=people," + B)))).isEqualTo(403);
    assertThat(memberUntil(carol, ENGINEERING, ALICE)).isEqualTo(today.plusDays(20).toString());
    assertThat(status(carol.post(until, Map.of("member", "uid=dave,ou=people," + B, "until", today.plusDays(7).toString())))).isEqualTo(404);
    assertThat(status(carol.post(until, Map.of("member", george, "until", today.minusDays(1).toString())))).isEqualTo(400);
    assertThat(status(carol.post(until, Map.of("until", today.plusDays(7).toString())))).isEqualTo(400);

    // Adding the person again without a date clears it; removing a member drops theirs.
    assertThat(status(carol.post(members, Map.of("add", List.of(ALICE), "remove", List.of())))).isEqualTo(200);
    assertThat(memberUntil(carol, ENGINEERING, ALICE)).isNull();
    assertThat(status(carol.post(until, Map.of("member", george, "until", today.plusDays(3).toString())))).isEqualTo(200);
    assertThat(status(carol.post(members, Map.of("add", List.of(), "remove", List.of(george))))).isEqualTo(200);
    assertThat(expiries.all()).isEmpty();
    // ... and so does leaving.
    assertThat(status(carol.post(members, Map.of("add", List.of(george), "remove", List.of(), "until", today.plusDays(3).toString())))).isEqualTo(200);
    assertThat(status(login("george").post("/api/groups/def-groups/leave" + q(ENGINEERING), null))).isEqualTo(200);
    assertThat(expiries.all()).isEmpty();

    // A member removed elsewhere takes the end date along: added again later, they stay.
    assertThat(status(carol.post(members, Map.of("add", List.of(george), "remove", List.of(), "until", today.plusDays(3).toString())))).isEqualTo(200);
    Client admin = login("admin");
    List<String> without = List.of(ALICE, "uid=carol,ou=people," + B, "uid=fatima,ou=people," + B);
    assertThat(status(admin.put("/api/directories/groups/entry" + q(ENGINEERING), Map.of("values", Map.of("member", without))))).isEqualTo(200);
    assertThat(expiries.all()).hasSize(1);
    assertThat(jobs.expireMemberships(Instant.now().plus(1, ChronoUnit.HOURS))).isZero();
    assertThat(expiries.all()).isEmpty();
    List<String> with = new ArrayList<>(without);
    with.add(george);
    assertThat(status(admin.put("/api/directories/groups/entry" + q(ENGINEERING), Map.of("values", Map.of("member", with))))).isEqualTo(200);
    assertThat(jobs.expireMemberships(at(today.plusDays(10), 0))).isZero();
    assertThat(member(login("george"), ENGINEERING)).isTrue();
  }

  @Test
  void theDirectoryLimitsHowLongMembershipsLast() throws Exception {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    String george = "uid=george,ou=people," + B;
    setGroups(groups(Map.of("maxMembershipDays", 30, "openFilter", "(cn=social-club)", "allowLeave", true)));
    Client dave = login("dave");
    Client carol = login("carol");
    assertThat(body(dave.get("/api/groups/directories")).get(0).get("maxMembershipDays").asInt()).isEqualTo(30);
    assertThat(body(dave.get("/api/groups/def-groups/group" + q(ENGINEERING))).get("maxMembershipDays").asInt()).isEqualTo(30);

    // No request, approval or added member gets more; without a date they get the longest.
    MvcResult tooLong = dave.post("/api/groups/def-groups/requests" + q(ENGINEERING), Map.of("until", today.plusDays(31).toString()));
    assertThat(status(tooLong)).isEqualTo(400);
    assertThat(body(tooLong).get("details").get("until").asString()).contains("at most 30 days");
    String id = ask(dave, ENGINEERING, null);
    assertThat(status(carol.post("/api/groups/requests/" + id + "/approve", Map.of("until", today.plusDays(31).toString())))).isEqualTo(400);
    assertThat(body(carol.post("/api/groups/requests/" + id + "/approve", Map.of())).get("grantedUntil").asString()).isEqualTo(today.plusDays(30).toString());
    assertThat(memberUntil(carol, ENGINEERING, "uid=dave,ou=people," + B)).isEqualTo(today.plusDays(30).toString());

    String members = "/api/groups/def-groups/members" + q(ENGINEERING);
    assertThat(status(carol.post(members, Map.of("add", List.of(george), "remove", List.of(), "until", today.plusDays(40).toString())))).isEqualTo(400);
    assertThat(member(login("george"), ENGINEERING)).isFalse();
    assertThat(status(carol.post(members, Map.of("add", List.of(george), "remove", List.of())))).isEqualTo(200);
    assertThat(memberUntil(carol, ENGINEERING, george)).isEqualTo(today.plusDays(30).toString());
    // An end date can be moved within the limit, not cleared.
    String until = "/api/groups/def-groups/members/until" + q(ENGINEERING);
    assertThat(status(carol.post(until, Map.of("member", george, "until", today.plusDays(12).toString())))).isEqualTo(200);
    MvcResult cleared = carol.post(until, Map.of("member", george));
    assertThat(status(cleared)).isEqualTo(400);
    assertThat(body(cleared).get("details").has("until")).isTrue();
    assertThat(memberUntil(carol, ENGINEERING, george)).isEqualTo(today.plusDays(12).toString());

    // Joining an open group is for the longest membership too.
    assertThat(status(dave.post("/api/groups/def-groups/join" + q(SOCIAL), null))).isEqualTo(200);
    assertThat(byName(body(dave.get("/api/groups/mine"))).get("social-club").get("memberUntil").asString()).isEqualTo(today.plusDays(30).toString());

    // An approval after the limit was lowered follows the limit, not the date asked for.
    String erins = body(login("erin").post("/api/groups/def-groups/requests" + q(ENGINEERING), Map.of("until", today.plusDays(25).toString())))
        .get("id").asString();
    setGroups(groups(Map.of("maxMembershipDays", 7)));
    assertThat(body(carol.post("/api/groups/requests/" + erins + "/approve", Map.of())).get("grantedUntil").asString())
        .isEqualTo(today.plusDays(7).toString());

    Map<String, Object> def = definitionInput();
    def.put("groups", groups(Map.of("maxMembershipDays", 3651)));
    assertThat(status(login("admin").put("/api/admin/definitions/def-groups", def))).isEqualTo(400);
    def.put("groups", groups(Map.of("maxMembershipDays", -1)));
    assertThat(status(login("admin").put("/api/admin/definitions/def-groups", def))).isEqualTo(400);
  }

  @Test
  void aMemberThatCannotBeRemovedIsReportedAndTriedAgainADayLater() throws Exception {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    String erinDn = "uid=erin,ou=people," + B;
    // Erin is social-club's owner and only member, and the form requires a member.
    Client erin = login("erin");
    assertThat(status(erin.post("/api/groups/def-groups/members/until" + q(SOCIAL), Map.of("member", erinDn, "until", today.toString())))).isEqualTo(200);
    assertThat(jobs.expireMemberships(at(today.plusDays(1), 0))).isZero();
    assertThat(member(erin, SOCIAL)).isTrue();
    assertThat(jobs.expireMemberships(at(today.plusDays(1), 5))).isZero();
    List<JsonNode> events = events("group.membership-expired", "member", erinDn);
    assertThat(events).hasSize(1);
    assertThat(events.get(0).get("outcome").asString()).isEqualTo("failure");
    assertThat(events.get(0).get("actor").get("type").asString()).isEqualTo("system");
    assertThat(expiries.all()).hasSize(1);
    assertThat(jobs.expireMemberships(at(today.plusDays(2), 1))).isZero();
    assertThat(events("group.membership-expired", "member", erinDn)).hasSize(2);

    // Once someone else is in the group, the next try removes her.
    assertThat(status(erin.post("/api/groups/def-groups/members" + q(SOCIAL), Map.of("add", List.of(ALICE), "remove", List.of())))).isEqualTo(200);
    assertThat(jobs.expireMemberships(at(today.plusDays(3), 2))).isEqualTo(1);
    assertThat(member(erin, SOCIAL)).isFalse();
    assertThat(expiries.all()).isEmpty();
    assertThat(events("group.membership-expired", "member", erinDn).get(0).get("outcome").asString()).isEqualTo("success");
  }

  @Test
  void ownersLookUpAPastedListOfPeople() throws Exception {
    String george = "uid=george,ou=people," + B;
    String dave = "uid=dave,ou=people," + B;
    // Two people share an address.
    directory.modify(george, List.of(new Modification(ModificationType.REPLACE, "mail", "dave@example.com")));
    Client carol = login("carol");
    String resolve = "/api/groups/def-groups/people/resolve" + q(ENGINEERING);
    List<String> lines = List.of("erin", " BOB@example.com ", george.toUpperCase(), "alice", "nobody", "uid=ghost,ou=people," + B, SALES,
        "dave@example.com", "", "erin@example.com", "erin");
    JsonNode r = body(carol.post(resolve, Map.of("lines", lines)));
    Map<String, JsonNode> found = new HashMap<>();
    r.get("found").forEach(f -> found.put(f.get("line").asString(), f));
    // By login, by e-mail and by DN; a person named twice is listed once; alice is a member already.
    assertThat(found).containsOnlyKeys("erin", "BOB@example.com", george.toUpperCase(), "alice");
    assertThat(found.get("erin").get("value").asString()).isEqualTo("uid=erin,ou=people," + B);
    assertThat(found.get("erin").get("name").asString()).isEqualTo("Erin Evans (erin)");
    assertThat(found.get("erin").get("member").asBoolean()).isFalse();
    assertThat(found.get("BOB@example.com").get("dn").asString()).isEqualTo("uid=bob,ou=people," + B);
    assertThat(found.get(george.toUpperCase()).get("value").asString()).isEqualTo(george);
    assertThat(found.get("alice").get("member").asBoolean()).isTrue();
    // Nobody, a DN that isn't there, and a group aren't people; an address two people have is ambiguous.
    List<String> notFound = new ArrayList<>();
    r.get("notFound").forEach(n -> notFound.add(n.asString()));
    assertThat(notFound).containsExactly("nobody", "uid=ghost,ou=people," + B, SALES);
    assertThat(r.get("ambiguous")).hasSize(1);
    assertThat(r.get("ambiguous").get(0).get("line").asString()).isEqualTo("dave@example.com");
    assertThat(r.get("ambiguous").get(0).get("matches")).hasSize(2);

    // The found people are added in one step, with an end date.
    LocalDate last = LocalDate.now(ZoneOffset.UTC).plusDays(14);
    List<String> values = new ArrayList<>();
    r.get("found").forEach(f -> values.add(f.get("value").asString()));
    MvcResult added = carol.post("/api/groups/def-groups/members" + q(ENGINEERING), Map.of("add", values, "remove", List.of(), "until", last.toString()));
    assertThat(status(added)).as(added.getResponse().getContentAsString()).isEqualTo(200);
    assertThat(body(added).get("added")).hasSize(3);
    assertThat(memberUntil(carol, ENGINEERING, george)).isEqualTo(last.toString());
    assertThat(member(login("erin"), ENGINEERING)).isTrue();
    assertThat(dave).isNotIn(values);

    // Only for those who manage the group, and for a list of a sensible length.
    assertThat(status(login("alice").post(resolve, Map.of("lines", List.of("erin"))))).isEqualTo(403);
    assertThat(status(login("bob").post(resolve, Map.of("lines", List.of("erin"))))).isEqualTo(403);
    assertThat(status(carol.post("/api/groups/def-groups/people/resolve" + q(SALES), Map.of("lines", List.of("erin"))))).isEqualTo(403);
    List<String> many = new ArrayList<>();
    for (int i = 0; i <= 200; i++) many.add("user" + i);
    assertThat(status(carol.post(resolve, Map.of("lines", many)))).isEqualTo(400);
    assertThat(status(carol.post(resolve, Map.of()))).isEqualTo(400);
    assertThat(body(carol.post(resolve, Map.of("lines", List.of(" ", "*", "(uid=*)", "a*"))))
        .get("found")).isEmpty();
  }

  @Test
  void severalRequestsAreDecidedTogetherEachOnItsOwn() throws Exception {
    Client carol = login("carol");
    Client admin = login("admin");
    String daves = ask(login("dave"), ENGINEERING, "On call");
    String erins = ask(login("erin"), ENGINEERING, "Curious");
    String fatimas = ask(login("fatima"), HR, "Benefits");
    String georges = ask(login("george"), SALES, "Demo support");
    String carols = ask(carol, SALES, "Forecasts");
    // Someone else answers dave's first.
    assertThat(status(admin.post("/api/groups/requests/" + daves + "/reject", Map.of()))).isEqualTo(200);

    MvcResult r = carol.post("/api/groups/requests/decide",
        Map.of("ids", List.of(daves, erins, georges, carols, "req-unknown", erins), "approve", true, "note", "Welcome aboard"));
    assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
    Map<String, JsonNode> results = new HashMap<>();
    body(r).get("results").forEach(x -> results.put(x.get("id").asString(), x));
    assertThat(body(r).get("results")).hasSize(5);
    assertThat(body(r).get("decided").asInt()).isEqualTo(1);
    assertThat(body(r).get("failed").asInt()).isEqualTo(4);
    // Carol's group: approved, with the one note.
    assertThat(results.get(erins).get("ok").asBoolean()).isTrue();
    assertThat(results.get(erins).get("status").asString()).isEqualTo("approved");
    assertThat(member(login("erin"), ENGINEERING)).isTrue();
    assertThat(requests.find(erins).note()).isEqualTo("Welcome aboard");
    assertThat(requests.find(erins).decidedBy()).isEqualTo("carol");
    // Already decided, not hers to decide (answered as if it weren't there), her own, and unknown.
    assertThat(results.get(daves).get("code").asInt()).isEqualTo(409);
    assertThat(results.get(daves).get("error").asString()).isEqualTo("This request was already rejected");
    assertThat(results.get(georges).get("code").asInt()).isEqualTo(404);
    assertThat(results.get(georges).get("error").asString()).isEqualTo(results.get("req-unknown").get("error").asString());
    assertThat(results.get(carols).get("code").asInt()).isEqualTo(403);
    assertThat(results.get(carols).get("error").asString()).isEqualTo("You can't decide your own request");
    assertThat(requests.find(georges).pending()).isTrue();
    assertThat(requests.find(carols).pending()).isTrue();
    assertThat(member(login("george"), SALES)).isFalse();
    assertThat(member(carol, SALES)).isFalse();
    // Each decision is audited as a single one is, and so is the refusal.
    assertThat(events("group.access-approved", "request", erins)).hasSize(1);
    assertThat(events("group.access-approved", "request", georges)).isEmpty();
    assertThat(events("access.denied", "path", "/api/groups/requests/decide")).isNotEmpty();

    // Rejecting together: the administrator decides any group's requests, but not without rights or beyond the limit.
    MvcResult none = login("bob").post("/api/groups/requests/decide", Map.of("ids", List.of(georges, fatimas), "approve", false));
    assertThat(body(none).get("decided").asInt()).isZero();
    body(none).get("results").forEach(x -> assertThat(x.get("code").asInt()).isEqualTo(404));
    MvcResult rejected = admin.post("/api/groups/requests/decide", Map.of("ids", List.of(georges, fatimas, carols), "approve", false, "note", "Not this quarter"));
    assertThat(body(rejected).get("decided").asInt()).isEqualTo(3);
    assertThat(requests.find(fatimas).status()).isEqualTo(AccessRequests.REJECTED);
    assertThat(requests.find(carols).note()).isEqualTo("Not this quarter");
    assertThat(member(login("fatima"), HR)).isFalse();

    List<String> many = new ArrayList<>();
    for (int i = 0; i <= 50; i++) many.add("req-" + i);
    assertThat(status(admin.post("/api/groups/requests/decide", Map.of("ids", many, "approve", true)))).isEqualTo(400);
    assertThat(status(admin.post("/api/groups/requests/decide", Map.of("ids", List.of(), "approve", true)))).isEqualTo(400);
    assertThat(status(admin.post("/api/groups/requests/decide", Map.of("ids", List.of(erins))))).isEqualTo(400);
    assertThat(status(admin.post("/api/groups/requests/decide", Map.of("ids", List.of(erins), "approve", true, "note", "x".repeat(1001))))).isEqualTo(400);
  }

  @Test
  void ownersExportTheirGroupsMembersAsCsv() throws Exception {
    LocalDate last = LocalDate.now(ZoneOffset.UTC).plusDays(9);
    String fatima = "uid=fatima,ou=people," + B;
    Client carol = login("carol");
    assertThat(status(carol.post("/api/groups/def-groups/members/until" + q(ENGINEERING), Map.of("member", fatima, "until", last.toString())))).isEqualTo(200);
    // A name a spreadsheet would run as a formula.
    directory.modify(fatima, List.of(new Modification(ModificationType.REPLACE, "displayName", "=HYPERLINK(\"http://x\",\"Fatima\")")));
    String url = "/api/groups/def-groups/members/export" + q(ENGINEERING);

    MvcResult export = carol.post(url, null);
    assertThat(status(export)).as(export.getResponse().getContentAsString()).isEqualTo(200);
    assertThat(export.getResponse().getContentType()).startsWith("text/csv");
    assertThat(export.getResponse().getHeader("Content-Disposition")).contains("attachment").contains("engineering-members-").contains(".csv");
    assertThat(export.getResponse().getHeader("X-DSP-Export-Count")).isEqualTo("3");
    assertThat(export.getResponse().getHeader("X-DSP-Export-Truncated")).isEqualTo("false");
    assertThat(export.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    String[] lines = export.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).split("\r\n");
    assertThat(lines[0]).isEqualTo("\uFEFFname,login,mail,dn,until");
    assertThat(lines[1]).isEqualTo("Alice Anderson,alice,alice@example.com,\"" + ALICE + "\",");
    assertThat(lines[2]).startsWith("Carol Chen,carol,carol@example.com,");
    // Guarded with an apostrophe, quoted, and with the end date.
    assertThat(lines[3]).isEqualTo("\"'=HYPERLINK(\"\"http://x\"\",\"\"Fatima\"\")\",fatima,fatima@example.com,\"" + fatima + "\"," + last);
    assertThat(lines).hasSize(4);

    // Recorded like the directory's member export, saying why an owner could make it.
    JsonNode event = events("data.exported", "via", "group-owner").get(0);
    assertThat(event.get("actor").get("id").asString()).isEqualTo("carol");
    assertThat(event.get("target").get("id").asString()).isEqualTo(ENGINEERING);
    assertThat(event.get("details").get("members").asInt()).isEqualTo(3);
    assertThat(event.get("details").get("format").asString()).isEqualTo("csv");
    int recorded = events("data.exported", "format", "csv").size();

    // Only for those who manage the group, only with the request header, and only as a POST.
    assertThat(status(login("alice").post(url, null))).isEqualTo(403);
    assertThat(status(login("bob").post(url, null))).isEqualTo(403);
    assertThat(status(carol.post("/api/groups/def-groups/members/export" + q(SALES), null))).isEqualTo(403);
    assertThat(status(carol.get(url))).isEqualTo(405);
    MvcResult forged = carol.send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(java.net.URI.create(url))
        .header("Origin", "https://attacker.example").header("Sec-Fetch-Site", "cross-site").contentType("application/x-www-form-urlencoded").content("x=1"));
    assertThat(status(forged)).isEqualTo(403);
    assertThat(forged.getResponse().getContentAsString()).doesNotContain("uid=");
    assertThat(events("data.exported", "format", "csv")).hasSize(recorded);

    // The directory editor's license gates it, as it gates the directory's member export; a read-only license doesn't.
    TestLicensing.use(TestLicenses.license().features("self-service-profile").sign());
    assertThat(status(carol.get("/api/groups/def-groups/members" + q(ENGINEERING)))).isEqualTo(200);
    MvcResult unlicensed = carol.post(url, null);
    assertThat(status(unlicensed)).isEqualTo(403);
    assertThat(body(unlicensed).get("code").asString()).isEqualTo("feature_not_licensed");
    TestLicensing.use(TestLicenses.license().expired().sign());
    assertThat(status(carol.post(url, null))).isEqualTo(200);
    assertThat(status(carol.post("/api/groups/def-groups/members" + q(ENGINEERING), Map.of("add", List.of(), "remove", List.of(fatima))))).isEqualTo(403);
    TestLicensing.use(TestLicenses.full());

    // More members than an export may hold: cut short, and said so.
    integration.getTransfer().setExportMaxEntries(2);
    try {
      MvcResult cut = login("admin").post(url, null);
      assertThat(cut.getResponse().getHeader("X-DSP-Export-Count")).isEqualTo("2");
      assertThat(cut.getResponse().getHeader("X-DSP-Export-Truncated")).isEqualTo("true");
    } finally {
      integration.getTransfer().setExportMaxEntries(10_000);
    }
  }

  @Test
  void ownersSeeTheirGroupsPastRequests() throws Exception {
    setGroups(groups(Map.of("requestExpiryDays", 14, "requireReason", true)));
    Client carol = login("carol");
    Client admin = login("admin");
    Client dave = login("dave");
    String history = "/api/groups/def-groups/group/history" + q(ENGINEERING);
    assertThat(body(carol.get(history)).get("total").asInt()).isZero();

    // Approved, rejected, withdrawn and expired; one still open, one for another group.
    String approved = ask(dave, ENGINEERING, "On call");
    assertThat(status(carol.post("/api/groups/requests/" + approved + "/approve", Map.of("note", "Welcome")))).isEqualTo(200);
    String rejected = ask(login("erin"), ENGINEERING, "Curious");
    assertThat(status(admin.post("/api/groups/requests/" + rejected + "/reject", Map.of("note", "Not for HR")))).isEqualTo(200);
    Client george = login("george");
    String withdrawn = ask(george, ENGINEERING, "Mistake");
    assertThat(status(george.post("/api/groups/requests/" + withdrawn + "/cancel", null))).isEqualTo(200);
    String expired = ask(login("bob"), ENGINEERING, "Some day");
    String open = ask(george, ENGINEERING, "Really");
    String other = ask(dave, SALES, "Demo");
    assertThat(status(admin.post("/api/groups/requests/" + other + "/reject", Map.of()))).isEqualTo(200);
    Instant late = Instant.now().plus(15, ChronoUnit.DAYS);
    assertThat(jobs.expireRequests(late)).isEqualTo(2);

    JsonNode page = body(carol.get(history));
    assertThat(page.get("total").asInt()).isEqualTo(5);
    assertThat(page.get("page").asInt()).isEqualTo(1);
    assertThat(page.get("pageSize").asInt()).isEqualTo(20);
    Map<String, JsonNode> byId = new HashMap<>();
    List<String> order = new ArrayList<>();
    page.get("items").forEach(v -> {
      byId.put(v.get("request").get("id").asString(), v.get("request"));
      order.add(v.get("request").get("id").asString());
      assertThat(v.get("canDecide").asBoolean()).isFalse();
    });
    // The most recently closed first (the two that expired, then the others backwards); never another group's.
    assertThat(order).hasSize(5).doesNotContain(other);
    assertThat(order.subList(0, 2)).containsExactlyInAnyOrder(expired, open);
    assertThat(order.subList(2, 5)).containsExactly(withdrawn, rejected, approved);
    assertThat(byId.get(approved).get("status").asString()).isEqualTo("approved");
    assertThat(byId.get(approved).get("requesterName").asString()).isEqualTo("Dave Diaz");
    assertThat(byId.get(approved).get("reason").asString()).isEqualTo("On call");
    assertThat(byId.get(approved).get("decidedByName").asString()).isEqualTo("Carol Chen");
    assertThat(byId.get(approved).get("note").asString()).isEqualTo("Welcome");
    assertThat(byId.get(rejected).get("decidedByName").asString()).isEqualTo("Ada Admin");
    assertThat(byId.get(withdrawn).get("status").asString()).isEqualTo("cancelled");
    assertThat(byId.get(expired).get("status").asString()).isEqualTo("expired");
    assertThat(byId.get(expired).get("expiredAfterDays").asInt()).isEqualTo(14);
    // The open requests keep their own list.
    assertThat(body(carol.get("/api/groups/def-groups/group/requests" + q(ENGINEERING)))).isEmpty();

    // A page at a time.
    for (int i = 0; i < 20; i++) {
      requests.close(requests.add("def-groups", ENGINEERING, "engineering", "u" + i, null, "User " + i, "uid=u" + i + ",ou=people," + B, null, 25).id(),
          AccessRequests.REJECTED, "admin", "Ada Admin", null);
    }
    JsonNode first = body(carol.get(history));
    assertThat(first.get("total").asInt()).isEqualTo(25);
    assertThat(first.get("items")).hasSize(20);
    JsonNode second = body(carol.get(history + "&page=2"));
    assertThat(second.get("items")).hasSize(5);
    assertThat(second.get("items").get(4).get("request").get("id").asString()).isEqualTo(approved);
    assertThat(body(carol.get(history + "&page=99")).get("items")).isEmpty();
    assertThat(body(carol.get(history + "&page=-3")).get("page").asInt()).isEqualTo(1);

    // Only for those who manage the group: members and requesters get nothing of it.
    assertThat(status(login("alice").get(history))).isEqualTo(403);
    assertThat(status(dave.get(history))).isEqualTo(403);
    assertThat(status(carol.get("/api/groups/def-groups/group/history" + q(SALES)))).isEqualTo(403);
    assertThat(body(admin.get("/api/groups/def-groups/group/history" + q(SALES))).get("total").asInt()).isEqualTo(1);
    assertThat(status(carol.get("/api/groups/def-groups/group/history" + q("cn=nope,ou=groups," + B)))).isEqualTo(404);
  }

  /** Names the unit's owners in {@code seeAlso} (the one DN attribute the standard organizationalUnit class allows). */
  void ownUnit(String unit, String... owners) {
    directory.modify(unit, List.of(new Modification(ModificationType.REPLACE, "seeAlso", owners)));
  }

  JsonNode entry(String dn) throws Exception {
    return body(login("admin").get("/api/directories/def-groups/entry" + q(dn))).get("attributes");
  }

  List<String> containers(Client c) throws Exception {
    List<String> out = new ArrayList<>();
    body(c.get("/api/groups/containers")).forEach(x -> out.add(x.get("dn").asString()));
    return out;
  }

  @Test
  void anOwnerOfAnOrganizationalUnitCreatesGroupsInIt() throws Exception {
    String carolDn = "uid=carol,ou=people," + B;
    String george = "uid=george,ou=people," + B;
    String adminDn = "uid=admin,ou=people," + B;
    Client carol = login("carol");
    Client bob = login("bob");
    ownUnit(GROUPS_OU, carolDn);
    Map<String, Object> group = Map.of("container", GROUPS_OU, "values", Map.of("cn", "project-x", "description", "Project X"));

    // Without the setting (as stored before there was one) nobody owns a unit, whatever the directory holds.
    setGroups(groups(Map.of()));
    assertThat(containers(carol)).isEmpty();
    assertThat(status(carol.get("/api/groups/def-groups/group/form"))).isEqualTo(403);
    assertThat(status(carol.post("/api/groups/def-groups/group", group))).isEqualTo(403);
    assertThat(body(carol.get("/api/groups/def-groups/group" + q(SALES))).get("canDelete").asBoolean()).isFalse();
    assertThat(status(carol.del("/api/groups/def-groups/group" + q(SALES)))).isEqualTo(403);

    setGroups(groups(Map.of("containerOwnerAttribute", "seeAlso")));
    JsonNode mine = body(carol.get("/api/groups/containers"));
    assertThat(mine).hasSize(1);
    assertThat(mine.get(0).get("definitionId").asString()).isEqualTo("def-groups");
    assertThat(mine.get(0).get("definitionName").asString()).isEqualTo("Groups");
    assertThat(mine.get(0).get("dn").asString()).isEqualTo(GROUPS_OU);
    assertThat(mine.get(0).get("name").asString()).isEqualTo("groups");
    // Others learn nothing: no units, no form, no options.
    assertThat(containers(bob)).isEmpty();
    assertThat(status(bob.get("/api/groups/def-groups/group/form"))).isEqualTo(403);
    assertThat(status(bob.get("/api/groups/def-groups/group/options/f-member?q=george"))).isEqualTo(403);

    // The definition's own form, with the creator as owner and (groupOfNames requires a member) first member.
    JsonNode form = body(carol.get("/api/groups/def-groups/group/form"));
    assertThat(form.get("form").get("id").asString()).isEqualTo("form-group");
    assertThat(form.get("prefill").get("owner").get(0).asString()).isEqualTo(carolDn);
    assertThat(form.get("prefill").get("member").get(0).asString()).isEqualTo(carolDn);
    JsonNode options = body(carol.get("/api/groups/def-groups/group/options/f-member?q=george"));
    assertThat(options.get("options").get(0).get("value").asString()).isEqualTo(george);
    assertThat(status(carol.get("/api/groups/def-groups/group/options/nope"))).isEqualTo(404);

    MvcResult[] created = new MvcResult[1];
    List<EntryChanged> changes = changesDuring(() -> created[0] = carol.post("/api/groups/def-groups/group", group));
    assertThat(status(created[0])).as(created[0].getResponse().getContentAsString()).isEqualTo(201);
    String dn = "cn=project-x,ou=groups," + B;
    assertThat(body(created[0]).get("dn").asString()).isEqualTo(dn);
    assertThat(body(created[0]).get("name").asString()).isEqualTo("project-x");
    assertThat(body(created[0]).get("definitionId").asString()).isEqualTo("def-groups");
    assertThat(entry(dn).get("owner").get(0).asString()).isEqualTo(carolDn);
    assertThat(entry(dn).get("member").get(0).asString()).isEqualTo(carolDn);
    assertThat(entry(dn).get("description").get(0).asString()).isEqualTo("Project X");
    JsonNode detail = body(carol.get("/api/groups/def-groups/group" + q(dn)));
    assertThat(detail.get("group").get("owner").asBoolean()).isTrue();
    assertThat(detail.get("group").get("member").asBoolean()).isTrue();
    assertThat(detail.get("canDelete").asBoolean()).isTrue();
    assertThat(body(bob.get("/api/groups/def-groups/group" + q(dn))).get("canDelete").asBoolean()).isFalse();

    // The usual entry.create, saying why someone without write access could make it, and the editors' webhook.
    JsonNode event = events("entry.create", "via", "group-container-owner").get(0);
    assertThat(event.get("actor").get("id").asString()).isEqualTo("carol");
    assertThat(event.get("target").get("id").asString()).isEqualTo(dn);
    assertThat(event.get("outcome").asString()).isEqualTo("success");
    assertThat(event.get("details").get("owner.new").asString()).isEqualTo(carolDn);
    assertThat(changes).hasSize(1);
    assertThat(changes.get(0).operation()).isEqualTo(EntryChanged.CREATE);
    assertThat(changes.get(0).dn()).isEqualTo(dn);

    // Owners and members the creator names are kept as given.
    MvcResult named = carol.post("/api/groups/def-groups/group",
        Map.of("container", GROUPS_OU, "values", Map.of("cn", "project-y", "owner", List.of(adminDn), "member", List.of(george))));
    assertThat(status(named)).as(named.getResponse().getContentAsString()).isEqualTo(201);
    JsonNode y = entry("cn=project-y,ou=groups," + B);
    assertThat(y.get("owner")).hasSize(1);
    assertThat(y.get("owner").get(0).asString()).isEqualTo(adminDn);
    assertThat(y.get("member")).hasSize(1);
    assertThat(y.get("member").get(0).asString()).isEqualTo(george);

    // The form's rules hold: a name is required, members are people of the directory, and names are taken once.
    MvcResult unnamed = carol.post("/api/groups/def-groups/group", Map.of("container", GROUPS_OU, "values", Map.of("description", "x")));
    assertThat(status(unnamed)).isEqualTo(400);
    assertThat(body(unnamed).get("details").has("cn")).isTrue();
    MvcResult stranger = carol.post("/api/groups/def-groups/group",
        Map.of("container", GROUPS_OU, "values", Map.of("cn", "project-z", "member", List.of("uid=nobody,ou=people," + B))));
    assertThat(status(stranger)).isEqualTo(400);
    assertThat(body(stranger).get("details").has("member")).isTrue();
    assertThat(status(carol.post("/api/groups/def-groups/group", group))).isEqualTo(409);
    assertThat(status(carol.post("/api/groups/def-groups/group", Map.of("values", Map.of("cn", "project-z"))))).isEqualTo(400);

    // Any other parent is refused alike: not hers, outside the directory, not a unit, not there, not a DN.
    for (String parent : List.of("ou=people," + B, B, ENGINEERING, "ou=nope,ou=groups," + B, "ou=groups", "not a dn")) {
      MvcResult refused = carol.post("/api/groups/def-groups/group", Map.of("container", parent, "values", Map.of("cn", "project-z")));
      assertThat(status(refused)).as(parent).isEqualTo(403);
      assertThat(body(refused).get("error").asString()).isEqualTo("Only an owner of an organizational unit can create groups in it");
    }
    // Refusals are audited like the module's other ones.
    assertThat(events("access.denied", "path", "/api/groups/def-groups/group")).isNotEmpty();
    assertThat(status(bob.post("/api/groups/def-groups/group", group))).isEqualTo(403);
    assertThat(status(carol.post("/api/groups/def-staff/group", group))).isEqualTo(404);
    assertThat(status(login("admin").get("/api/directories/def-groups/entry" + q("cn=project-z,ou=groups," + B)))).isEqualTo(404);

    // Editors who own no unit keep creating in the directory editor, as before (no via).
    Client admin = login("admin");
    assertThat(containers(admin)).isEmpty();
    assertThat(status(admin.post("/api/groups/def-groups/group", Map.of("container", GROUPS_OU, "values", Map.of("cn", "project-z"))))).isEqualTo(403);
    MvcResult edited = admin.post("/api/directories/def-groups/entries", Map.of("values", Map.of("cn", "project-z", "member", List.of(adminDn))));
    assertThat(status(edited)).as(edited.getResponse().getContentAsString()).isEqualTo(201);
    assertThat(body(admin.get("/api/admin/audit?action=entry.create")).get("events").get(0).get("details").has("via")).isFalse();
    // ... and carol, who owns the unit, still has no write access to the directory itself.
    assertThat(status(carol.post("/api/directories/def-groups/entries", Map.of("values", Map.of("cn", "project-w", "member", List.of(carolDn)))))).isEqualTo(403);
    assertThat(status(carol.del("/api/directories/def-groups/entry" + q(dn)))).isEqualTo(403);

    // Creating and deleting need the directory editor's license, and one that isn't read-only.
    TestLicensing.use(TestLicenses.license().features("self-service-profile").sign());
    MvcResult unlicensed = carol.post("/api/groups/def-groups/group", Map.of("container", GROUPS_OU, "values", Map.of("cn", "project-v")));
    assertThat(status(unlicensed)).isEqualTo(403);
    assertThat(body(unlicensed).get("code").asString()).isEqualTo("feature_not_licensed");
    assertThat(body(carol.del("/api/groups/def-groups/group" + q(dn))).get("code").asString()).isEqualTo("feature_not_licensed");
    assertThat(status(carol.get("/api/groups/containers"))).isEqualTo(403);
    TestLicensing.use(TestLicenses.license().expired().sign());
    assertThat(containers(carol)).containsExactly(GROUPS_OU);
    MvcResult readOnly = carol.post("/api/groups/def-groups/group", Map.of("container", GROUPS_OU, "values", Map.of("cn", "project-v")));
    assertThat(body(readOnly).get("code").asString()).isEqualTo("license_read_only");
    assertThat(body(carol.del("/api/groups/def-groups/group" + q(dn))).get("code").asString()).isEqualTo("license_read_only");
    TestLicensing.use(TestLicenses.full());
    assertThat(status(admin.get("/api/directories/def-groups/entry" + q(dn)))).isEqualTo(200);
  }

  @Test
  void theCreatorOwnsTheNewGroupAlsoWhereTheFormHasNoOwnerField() throws Exception {
    String carolDn = "uid=carol,ou=people," + B;
    ownUnit(GROUPS_OU, carolDn);
    Client carol = login("carol");
    // The form holds no owners: the owner attribute is set all the same, as groupOfNames allows it.
    store.update(d -> {
      for (int i = 0; i < d.forms.size(); i++) {
        com.winllc.dsp.model.FormDefinition f = d.forms.get(i);
        if (!f.id().equals("form-group")) continue;
        d.forms.set(i, new com.winllc.dsp.model.FormDefinition(f.id(), f.name(), f.description(), f.objectClasses(), f.rdnAttribute(),
            f.fields().stream().filter(x -> !x.attribute().equalsIgnoreCase("owner")).toList(), f.createdAt(), f.updatedAt()));
      }
      return null;
    });
    setGroups(groups(Map.of("containerOwnerAttribute", "seeAlso")));
    MvcResult made = carol.post("/api/groups/def-groups/group", Map.of("container", GROUPS_OU, "values", Map.of("cn", "project-x")));
    assertThat(status(made)).as(made.getResponse().getContentAsString()).isEqualTo(201);
    String dn = "cn=project-x,ou=groups," + B;
    assertThat(directory.get(dn, List.of("owner")).values("owner")).containsExactly(carolDn);
    assertThat(body(carol.get("/api/groups/def-groups/group" + q(dn))).get("group").get("owner").asBoolean()).isTrue();
    assertThat(events("entry.create", "via", "group-container-owner").get(0).get("details").get("owner.new").asString()).isEqualTo(carolDn);

    // An owner attribute the group's object classes don't take is left out: the group is created without an owner.
    setGroups(groups(Map.of("containerOwnerAttribute", "seeAlso", "ownerAttribute", "manager")));
    MvcResult plain = carol.post("/api/groups/def-groups/group", Map.of("container", GROUPS_OU, "values", Map.of("cn", "project-y")));
    assertThat(status(plain)).as(plain.getResponse().getContentAsString()).isEqualTo(201);
    JsonNode detail = body(carol.get("/api/groups/def-groups/group" + q("cn=project-y,ou=groups," + B)));
    assertThat(detail.get("group").get("owner").asBoolean()).isFalse();
    assertThat(detail.get("group").get("member").asBoolean()).isTrue();
    assertThat(detail.get("canDelete").asBoolean()).isTrue();
  }

  @Test
  void anOwnerOfAUnitDeletesOnlyTheGroupsDirectlyInIt() throws Exception {
    String carolDn = "uid=carol,ou=people," + B;
    String daveDn = "uid=dave,ou=people," + B;
    String teams = "ou=teams,ou=groups," + B;
    String other = "ou=other,ou=groups," + B;
    String teamA = "cn=team-a," + teams;
    String otherA = "cn=other-a," + other;
    for (String unit : List.of(teams, other)) {
      directory.add(unit, Map.of("objectClass", List.of("top", "organizationalUnit"), "ou", List.of(unit.substring(3, unit.indexOf(',')))));
    }
    for (String g : List.of(teamA, otherA)) {
      directory.add(g, Map.of("objectClass", List.of("top", "groupOfNames"), "cn", List.of(g.substring(3, g.indexOf(','))),
          "member", List.of(ALICE), "owner", List.of("uid=admin,ou=people," + B)));
    }
    // The whole subtree: the units below the base DN are containers too.
    Map<String, Object> def = definitionInput();
    def.put("scope", "sub");
    def.put("groups", groups(Map.of("containerOwnerAttribute", "seeAlso")));
    assertThat(status(login("admin").put("/api/admin/definitions/def-groups", def))).isEqualTo(200);
    ownUnit(GROUPS_OU, carolDn);
    ownUnit(teams, daveDn);
    // An owner attribute on anything but a container gives nothing.
    directory.modify(SALES, List.of(new Modification(ModificationType.ADD, "seeAlso", "uid=bob,ou=people," + B)));

    Client carol = login("carol");
    Client dave = login("dave");
    Client bob = login("bob");
    assertThat(containers(carol)).containsExactly(GROUPS_OU);
    assertThat(containers(dave)).containsExactly(teams);
    assertThat(containers(bob)).isEmpty();
    assertThat(status(bob.post("/api/groups/def-groups/group", Map.of("container", SALES, "values", Map.of("cn", "nested"))))).isEqualTo(403);

    // Carol owns ou=groups: its groups are hers to delete, those of the units below are not.
    assertThat(body(carol.get("/api/groups/def-groups/group" + q(ENGINEERING))).get("canDelete").asBoolean()).isTrue();
    assertThat(body(carol.get("/api/groups/def-groups/group" + q(teamA))).get("canDelete").asBoolean()).isFalse();
    assertThat(status(carol.del("/api/groups/def-groups/group" + q(teamA)))).isEqualTo(403);
    assertThat(status(carol.del("/api/groups/def-groups/group" + q(otherA)))).isEqualTo(403);
    assertThat(status(carol.post("/api/groups/def-groups/group", Map.of("container", teams, "values", Map.of("cn", "team-b"))))).isEqualTo(403);
    assertThat(status(carol.post("/api/groups/def-groups/group", Map.of("container", other, "values", Map.of("cn", "other-b"))))).isEqualTo(403);
    // Dave owns ou=teams only.
    assertThat(body(dave.get("/api/groups/def-groups/group" + q(teamA))).get("canDelete").asBoolean()).isTrue();
    assertThat(body(dave.get("/api/groups/def-groups/group" + q(ENGINEERING))).get("canDelete").asBoolean()).isFalse();
    assertThat(status(dave.del("/api/groups/def-groups/group" + q(ENGINEERING)))).isEqualTo(403);
    assertThat(status(dave.del("/api/groups/def-groups/group" + q(otherA)))).isEqualTo(403);
    assertThat(status(dave.post("/api/groups/def-groups/group", Map.of("container", GROUPS_OU, "values", Map.of("cn", "top-b"))))).isEqualTo(403);
    MvcResult made = dave.post("/api/groups/def-groups/group", Map.of("container", teams, "values", Map.of("cn", "team-b")));
    assertThat(status(made)).as(made.getResponse().getContentAsString()).isEqualTo(201);
    assertThat(body(made).get("dn").asString()).isEqualTo("cn=team-b," + teams);
    // Missing groups and those who own nothing get the answers they got before.
    assertThat(status(carol.del("/api/groups/def-groups/group" + q("cn=nope,ou=groups," + B)))).isEqualTo(404);
    assertThat(status(bob.del("/api/groups/def-groups/group" + q(ENGINEERING)))).isEqualTo(403);
    assertThat(status(login("alice").del("/api/groups/def-groups/group" + q(ENGINEERING)))).isEqualTo(403);
    assertThat(status(carol.del("/api/groups/def-groups/group"))).isEqualTo(400);
    for (String kept : List.of(ENGINEERING, teamA, otherA)) assertThat(status(login("admin").get("/api/directories/def-groups/entry" + q(kept)))).as(kept).isEqualTo(200);

    MvcResult[] deleted = new MvcResult[1];
    List<EntryChanged> changes = changesDuring(() -> deleted[0] = dave.del("/api/groups/def-groups/group" + q(teamA)));
    assertThat(status(deleted[0])).as(deleted[0].getResponse().getContentAsString()).isEqualTo(204);
    assertThat(status(login("admin").get("/api/directories/def-groups/entry" + q(teamA)))).isEqualTo(404);
    assertThat(status(dave.get("/api/groups/def-groups/group" + q(teamA)))).isEqualTo(404);
    // The usual entry.delete (what the group held, so it can be put back), with the reason and the webhook.
    JsonNode event = events("entry.delete", "via", "group-container-owner").get(0);
    assertThat(event.get("actor").get("id").asString()).isEqualTo("dave");
    assertThat(event.get("target").get("id").asString()).isEqualTo(teamA);
    assertThat(event.get("details").get("member.old").asString()).isEqualTo(ALICE);
    assertThat(changes).hasSize(1);
    assertThat(changes.get(0).operation()).isEqualTo(EntryChanged.DELETE);
    assertThat(changes.get(0).dn()).isEqualTo(teamA);

    // The directory is asked at the time of the request: an owner who was removed deletes nothing more.
    ownUnit(teams, carolDn);
    assertThat(status(dave.del("/api/groups/def-groups/group" + q("cn=team-b," + teams)))).isEqualTo(403);
    assertThat(containers(dave)).isEmpty();
    assertThat(status(carol.del("/api/groups/def-groups/group" + q("cn=team-b," + teams)))).isEqualTo(204);

    // With one level only the base DN's entry is a container, as only its children are groups of the directory.
    def.put("scope", "one");
    assertThat(status(login("admin").put("/api/admin/definitions/def-groups", def))).isEqualTo(200);
    assertThat(containers(carol)).containsExactly(GROUPS_OU);
    assertThat(status(carol.post("/api/groups/def-groups/group", Map.of("container", teams, "values", Map.of("cn", "team-c"))))).isEqualTo(403);
    assertThat(status(carol.del("/api/groups/def-groups/group" + q(ENGINEERING)))).isEqualTo(204);
    assertThat(status(login("admin").del("/api/directories/def-groups/entry" + q(SALES)))).isEqualTo(204);
    assertThat(body(login("admin").get("/api/admin/audit?action=entry.delete")).get("events").get(0).get("details").has("via")).isFalse();
  }

  @Test
  void aUnitIsOwnedThroughAGroupToo() throws Exception {
    setGroups(groups(Map.of("containerOwnerAttribute", "seeAlso")));
    // The members of hr all count as owners (erin is one, bob is not), as with a group's owner attribute.
    ownUnit(GROUPS_OU, HR);
    Client erin = login("erin");
    Client bob = login("bob");
    assertThat(containers(erin)).containsExactly(GROUPS_OU);
    assertThat(containers(bob)).isEmpty();
    assertThat(containers(login("carol"))).isEmpty();
    MvcResult made = erin.post("/api/groups/def-groups/group", Map.of("container", GROUPS_OU, "values", Map.of("cn", "hr-projects")));
    assertThat(status(made)).as(made.getResponse().getContentAsString()).isEqualTo(201);
    String dn = "cn=hr-projects,ou=groups," + B;
    // The creator herself owns the new group, not the group she owns the unit through.
    assertThat(entry(dn).get("owner").get(0).asString()).isEqualTo("uid=erin,ou=people," + B);
    assertThat(status(bob.post("/api/groups/def-groups/group", Map.of("container", GROUPS_OU, "values", Map.of("cn", "bobs"))))).isEqualTo(403);
    assertThat(status(bob.del("/api/groups/def-groups/group" + q(dn)))).isEqualTo(403);
    assertThat(body(erin.get("/api/groups/def-groups/group" + q(SALES))).get("canDelete").asBoolean()).isTrue();
    assertThat(status(erin.del("/api/groups/def-groups/group" + q(dn)))).isEqualTo(204);
  }

  @Test
  void deletingAGroupClosesItsOpenRequestsAndDropsItsEndDates() throws Exception {
    String carolDn = "uid=carol,ou=people," + B;
    String bobDn = "uid=bob,ou=people," + B;
    setGroups(groups(Map.of("containerOwnerAttribute", "seeAlso")));
    ownUnit(GROUPS_OU, carolDn);
    Client carol = login("carol");
    Client admin = login("admin");
    Client dave = login("dave");
    String open = ask(dave, SALES, "Demo support");
    String decided = ask(login("erin"), SALES, "Curious");
    assertThat(status(admin.post("/api/groups/requests/" + decided + "/reject", Map.of("note", "Not for HR")))).isEqualTo(200);
    String elsewhere = ask(dave, HR, "Benefits");
    LocalDate last = LocalDate.now(ZoneOffset.UTC).plusDays(9);
    assertThat(status(admin.post("/api/groups/def-groups/members/until" + q(SALES), Map.of("member", bobDn, "until", last.toString())))).isEqualTo(200);
    assertThat(status(admin.post("/api/groups/def-groups/members/until" + q(HR), Map.of("member", "uid=erin,ou=people," + B, "until", last.toString()))))
        .isEqualTo(200);
    assertThat(expiries.ofGroup("def-groups", SALES)).hasSize(1);

    assertThat(status(carol.del("/api/groups/def-groups/group" + q(SALES)))).isEqualTo(204);

    // The open request is answered, so it doesn't wait for a group that is gone; the requester sees why.
    AccessRequests.Request closed = requests.find(open);
    assertThat(closed.status()).isEqualTo(AccessRequests.REJECTED);
    assertThat(closed.note()).isEqualTo("The group was deleted.");
    assertThat(closed.decidedBy()).isEqualTo("carol");
    JsonNode shown = body(dave.get("/api/groups/requests")).valueStream().filter(v -> v.get("request").get("id").asString().equals(open)).findFirst()
        .orElseThrow().get("request");
    assertThat(shown.get("decidedByName").asString()).isEqualTo("Carol Chen");
    assertThat(shown.get("note").asString()).isEqualTo("The group was deleted.");
    JsonNode event = events("group.access-rejected", "request", open).get(0);
    assertThat(event.get("actor").get("id").asString()).isEqualTo("carol");
    assertThat(event.get("details").get("via").asString()).isEqualTo("group-container-owner");
    assertThat(body(admin.get("/api/groups/requests/summary")).get("toReview").asInt()).isEqualTo(1);
    // What was decided before, and other groups' requests and end dates, stay as they are.
    assertThat(requests.find(decided).note()).isEqualTo("Not for HR");
    assertThat(requests.find(elsewhere).pending()).isTrue();
    assertThat(expiries.ofGroup("def-groups", SALES)).isEmpty();
    assertThat(expiries.ofGroup("def-groups", HR)).hasSize(1);
  }

  @Test
  void validatesTheSettingsAndCanBeTurnedOff() throws Exception {
    Client admin = login("admin");
    Map<String, Object> def = definitionInput();
    def.put("groups", groups(Map.of("memberAttribute", "cn")));
    assertThat(body(admin.put("/api/admin/definitions/def-groups", def)).get("details").has("groups.memberAttribute")).isTrue();
    def.put("groups", groups(Map.of("ownerAttribute", "member")));
    assertThat(body(admin.put("/api/admin/definitions/def-groups", def)).get("details").has("groups.ownerAttribute")).isTrue();
    // Owners change owners through the form's owner field.
    def.put("groups", groups(Map.of("ownersManageOwners", true, "ownerAttribute", "seeAlso")));
    assertThat(body(admin.put("/api/admin/definitions/def-groups", def)).get("details").has("groups.ownersManageOwners")).isTrue();
    def.put("groups", groups(Map.of("requestFilter", "(oops")));
    assertThat(status(admin.put("/api/admin/definitions/def-groups", def))).isEqualTo(400);
    // The attribute naming a unit's owners: an attribute name, and never a password.
    def.put("groups", groups(Map.of("containerOwnerAttribute", "managed by")));
    assertThat(status(admin.put("/api/admin/definitions/def-groups", def))).isEqualTo(400);
    def.put("groups", groups(Map.of("containerOwnerAttribute", "userPassword")));
    assertThat(body(admin.put("/api/admin/definitions/def-groups", def)).get("details").has("groups.containerOwnerAttribute")).isTrue();
    def.put("groups", groups(Map.of("containerOwnerAttribute", "managedBy")));
    assertThat(body(admin.put("/api/admin/definitions/def-groups", def)).get("groups").get("containerOwnerAttribute").asString()).isEqualTo("managedBy");
    def.put("groups", groups(Map.of()));
    def.put("mode", "readonly");
    assertThat(body(admin.put("/api/admin/definitions/def-groups", def)).get("details").has("groups.enabled")).isTrue();

    setGroups(Map.of("enabled", false, "requireReason", false, "allowLeave", false));
    Client alice = login("alice");
    assertThat(body(alice.get("/api/groups/directories"))).isEmpty();
    assertThat(status(alice.get("/api/groups/def-groups/group" + q(ENGINEERING)))).isEqualTo(404);
  }

  @Test
  void readsRequestsAndSettingsStoredBeforeTheNewFields() throws Exception {
    // Exactly what the first release wrote.
    java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("dsp-requests");
    java.nio.file.Files.writeString(dir.resolve("group-access-requests.json"), """
        [{"id":"req-1","definitionId":"def-groups","groupDn":"cn=engineering,ou=groups,dc=example,dc=com","groupName":"engineering",
          "requester":"dave","requesterDn":"uid=dave,ou=people,dc=example,dc=com","requesterName":"Dave Diaz",
          "member":"uid=dave,ou=people,dc=example,dc=com","reason":"On-call","status":"pending","createdAt":"2026-01-05T10:00:00.000Z"},
         {"id":"req-2","definitionId":"def-groups","groupDn":"cn=sales,ou=groups,dc=example,dc=com","groupName":"sales","requester":"erin",
          "member":"uid=erin,ou=people,dc=example,dc=com","status":"rejected","createdAt":"2026-01-05T10:00:00.000Z",
          "decidedAt":"2026-01-06T10:00:00.000Z","decidedBy":"admin","decidedByName":"Ada Admin","note":"No"}]""");
    com.winllc.dsp.config.PortalProperties props = new com.winllc.dsp.config.PortalProperties();
    props.setDataDir(dir.toString());
    AccessRequests stored = new AccessRequests(props, mapper, com.winllc.dsp.cluster.Cluster.standalone());
    assertThat(stored.list()).extracting(AccessRequests.Request::id).containsExactly("req-2", "req-1");
    assertThat(stored.find("req-1").pending()).isTrue();
    assertThat(stored.find("req-2").note()).isEqualTo("No");

    com.winllc.dsp.model.GroupAccess settings = mapper.readValue(
        "{\"enabled\":true,\"memberAttribute\":\"member\",\"requireReason\":true,\"allowLeave\":true}", com.winllc.dsp.model.GroupAccess.class);
    assertThat(settings.enabled()).isTrue();
    assertThat(settings.ownersManage()).isFalse();
    assertThat(settings.requestExpiry()).isZero();
    assertThat(settings.openFilter()).isNull();
    assertThat(settings.maxMembership()).isZero();
    assertThat(settings.containerOwner()).isNull();
    assertThat(settings.cleaned().containerOwnerAttribute()).isNull();
  }

  /** Enabled group access with the given settings (the booleans are always sent, as by the editor). */
  static Map<String, Object> groups(Map<String, Object> settings) {
    Map<String, Object> g = new HashMap<>(Map.of("enabled", true, "requireReason", false, "allowLeave", false));
    g.putAll(settings);
    return g;
  }

  /** The demo Groups definition as the editor sends it. */
  Map<String, Object> definitionInput() throws Exception {
    JsonNode d = body(login("admin").get("/api/admin/definitions")).valueStream().filter(x -> x.get("id").asString().equals("def-groups"))
        .findFirst().orElseThrow();
    @SuppressWarnings("unchecked")
    Map<String, Object> in = new HashMap<>(mapper.convertValue(d, Map.class));
    in.keySet().removeAll(List.of("id", "createdAt", "updatedAt"));
    return in;
  }

  void setGroups(Map<String, Object> groups) throws Exception {
    Map<String, Object> def = definitionInput();
    def.put("groups", groups);
    MvcResult r = login("admin").put("/api/admin/definitions/def-groups", def);
    assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
    DirectoryDefinition saved = store.snapshot().definitions.stream().filter(x -> x.id().equals("def-groups")).findFirst().orElseThrow();
    assertThat(saved.groups().enabled()).isEqualTo(groups.get("enabled"));
  }
}
