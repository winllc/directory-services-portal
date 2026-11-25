package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.dsp.groups.AccessRequests;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.service.LoginThrottle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** Self-service group access: lookup, requests, owner approval, leaving and owner-managed members (demo: Groups, owners admin and carol). */
class GroupsTest extends ApiTestSupport {
  static final String ENGINEERING = "cn=engineering,ou=groups," + B;
  static final String SALES = "cn=sales,ou=groups," + B;
  static final String HR = "cn=hr,ou=groups," + B;
  static final String ADMINS = "cn=directory-admins,ou=groups," + B;

  @Autowired AccessRequests requests;
  @Autowired LoginThrottle throttle;
  @Autowired com.winllc.dsp.groups.GovernanceClient governance;

  @BeforeEach
  void noRequests() {
    requests.clear();
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
  void validatesTheSettingsAndCanBeTurnedOff() throws Exception {
    Client admin = login("admin");
    Map<String, Object> def = definitionInput();
    def.put("groups", groups(Map.of("memberAttribute", "cn")));
    assertThat(body(admin.put("/api/admin/definitions/def-groups", def)).get("details").has("groups.memberAttribute")).isTrue();
    def.put("groups", groups(Map.of("ownerAttribute", "member")));
    assertThat(body(admin.put("/api/admin/definitions/def-groups", def)).get("details").has("groups.ownerAttribute")).isTrue();
    def.put("groups", groups(Map.of("requestFilter", "(oops")));
    assertThat(status(admin.put("/api/admin/definitions/def-groups", def))).isEqualTo(400);
    def.put("groups", groups(Map.of()));
    def.put("mode", "readonly");
    assertThat(body(admin.put("/api/admin/definitions/def-groups", def)).get("details").has("groups.enabled")).isTrue();

    setGroups(Map.of("enabled", false, "requireReason", false, "allowLeave", false));
    Client alice = login("alice");
    assertThat(body(alice.get("/api/groups/directories"))).isEmpty();
    assertThat(status(alice.get("/api/groups/def-groups/group" + q(ENGINEERING)))).isEqualTo(404);
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
