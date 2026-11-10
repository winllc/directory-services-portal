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
