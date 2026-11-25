package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.dsp.groups.AccessRequests;
import com.winllc.dsp.groups.GroupAccessJobs;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.service.LoginThrottle;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
  @Autowired GroupAccessJobs jobs;
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

    JsonNode events = body(login("admin").get("/api/admin/audit?action=group.access-expired")).get("events");
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
