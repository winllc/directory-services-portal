package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;

import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedModifyRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
import com.unboundid.ldap.sdk.Modification;
import com.winllc.dsp.ldap.LdapDirectory;
import com.winllc.dsp.model.DirectoryEntry;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Changing directory entries end to end: who may change what, what actually reaches the directory
 * (the modify requests the embedded LDAP server receives, and the entry read back from it with every
 * attribute), and what the audit trail then says (actor, entry, name, old and new values).
 */
class EntryChangesTest extends ApiTestSupport {
  static final String BOB = "uid=bob,ou=people," + B;
  static final String CAROL = "uid=carol,ou=people," + B;
  static final String FATIMA = "uid=fatima,ou=people," + B;
  static final String ENGINEERING = "cn=engineering,ou=groups," + B;
  static final String HR = "cn=hr,ou=groups," + B;

  @Autowired LdapDirectory directory;

  /** Every modify request the directory received in this test: "dn: TYPE attribute [values]". */
  private final List<String> modifies = new CopyOnWriteArrayList<>();
  private String since;

  @BeforeEach
  void watchTheDirectory() throws InterruptedException {
    embedded.intercept(new InMemoryOperationInterceptor() {
      @Override
      public void processModifyRequest(InMemoryInterceptedModifyRequest request) {
        for (Modification m : request.getRequest().getModifications()) {
          modifies.add(request.getRequest().getDN() + ": " + m.getModificationType().getName() + " " + m.getAttributeName() + " "
              + Arrays.toString(m.getValues()));
        }
      }
    });
    since = Instant.now().toString();
    Thread.sleep(2);
  }

  private DirectoryEntry read(String dn) {
    return directory.get(dn, List.of("*"));
  }

  /** The entry's attributes without the named ones. */
  private static Map<String, List<String>> without(DirectoryEntry e, String... attributes) {
    Map<String, List<String>> m = new HashMap<>(e.attributes());
    for (String a : attributes) m.remove(a.toLowerCase());
    return m;
  }

  /** Entry events about {@code dn} since the test started, newest first. */
  private List<JsonNode> trail(String dn) throws Exception {
    Client admin = login("admin");
    MvcResult r = admin.get("/api/admin/audit?pageSize=100&action=entry&from=" + URLEncoder.encode(since, StandardCharsets.UTF_8)
        + "&target=" + URLEncoder.encode(dn, StandardCharsets.UTF_8).replace("+", "%20"));
    assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
    List<JsonNode> out = new ArrayList<>();
    body(r).get("events").forEach(out::add);
    return out;
  }

  private static List<String> detailKeys(JsonNode event) {
    List<String> keys = new ArrayList<>();
    event.get("details").propertyNames().forEach(keys::add);
    return keys;
  }

  private static String detail(JsonNode event, String key) {
    JsonNode v = event.get("details").get(key);
    return v == null || v.isNull() ? null : v.asString();
  }

  private static String error(MvcResult r) throws Exception {
    return r.getResponse().getContentAsString();
  }

  @Test
  void aWriterChangesOnlyWhatTheySentAndTheTrailNamesThePersonTheEntryAndTheValues() throws Exception {
    DirectoryEntry before = read(ALICE);
    Client erin = login("erin"); // hr: write on Staff

    Map<String, Object> values = new HashMap<>();
    values.put("title", "Staff Engineer");
    values.put("mail", List.of("alice@example.com")); // unchanged
    values.put("acmeBadgeNumber", "B-9999"); // read-only in the form
    values.put("employeeType", "Contractor"); // not in the form
    MvcResult r = erin.put("/api/directories/staff/entry" + q(ALICE), Map.of("values", values));
    assertThat(status(r)).as(error(r)).isEqualTo(200);

    // One value replaced, nothing else sent; every other attribute as it was.
    assertThat(modifies).containsExactly(ALICE + ": REPLACE title [Staff Engineer]");
    DirectoryEntry after = read(ALICE);
    assertThat(after.values("title")).containsExactly("Staff Engineer");
    assertThat(without(after, "title")).isEqualTo(without(before, "title"));
    assertThat(after.values("acmeBadgeNumber")).containsExactly("B-1001");
    assertThat(after.values("employeeType")).containsExactly("Full time");

    List<JsonNode> trail = trail(ALICE);
    assertThat(trail).hasSize(1);
    JsonNode event = trail.get(0);
    assertThat(event.get("action").asString()).isEqualTo("entry.update");
    assertThat(event.get("outcome").asString()).isEqualTo("success");
    assertThat(event.get("actor").get("id").asString()).isEqualTo("erin");
    assertThat(detail(event, "actorDn")).isEqualTo("uid=erin,ou=people," + B);
    assertThat(event.get("target").get("id").asString()).isEqualTo(ALICE);
    assertThat(event.get("target").get("name").asString()).isEqualTo("Alice Anderson");
    assertThat(detail(event, "definitionId")).isEqualTo("def-staff");
    assertThat(detail(event, "title.old")).isEqualTo("Senior Software Engineer");
    assertThat(detail(event, "title.new")).isEqualTo("Staff Engineer");
    assertThat(detailKeys(event)).noneMatch(k -> k.startsWith("mail.") || k.startsWith("acmeBadgeNumber.") || k.startsWith("employeeType."));
  }

  @Test
  void refusedWritesChangeNothingInTheDirectoryAndAreRecordedAsDenied() throws Exception {
    DirectoryEntry alice = read(ALICE);
    DirectoryEntry bob = read(BOB);

    Client aliceClient = login("alice"); // engineering: read on Staff
    MvcResult update = aliceClient.put("/api/directories/staff/entry" + q(BOB), Map.of("values", Map.of("title", "CEO")));
    assertThat(status(update)).isEqualTo(403);
    assertThat(body(update).get("error").asString()).isEqualTo("You do not have write access to this directory");
    MvcResult delete = aliceClient.del("/api/directories/staff/entry" + q(BOB));
    assertThat(status(delete)).isEqualTo(403);
    // Self service only reaches the user's own entry, whatever DN is asked for.
    MvcResult notHers = aliceClient.put("/api/me/def-staff/entry" + q(BOB), Map.of("values", Map.of("mobile", List.of("+1 555 0000"))));
    assertThat(status(notHers)).isEqualTo(403);
    assertThat(body(notHers).get("error").asString()).isEqualTo("You can only edit your own entry");

    Client bobClient = login("bob"); // no grant on Staff
    assertThat(status(bobClient.put("/api/directories/staff/entry" + q(ALICE), Map.of("values", Map.of("title", "x"))))).isEqualTo(403);
    // White pages: never written through, not even by an administrator.
    MvcResult whitePages = login("admin").put("/api/directories/white-pages/entry" + q(ALICE), Map.of("values", Map.of("title", "x")));
    assertThat(status(whitePages)).isEqualTo(403);
    assertThat(body(whitePages).get("error").asString()).isEqualTo("This directory is read only");

    assertThat(modifies).isEmpty();
    assertThat(read(ALICE)).isEqualTo(alice);
    assertThat(read(BOB)).isEqualTo(bob);

    List<JsonNode> trail = trail(BOB);
    assertThat(trail).extracting(e -> e.get("action").asString() + " " + e.get("outcome").asString() + " " + e.get("actor").get("id").asString())
        .containsExactly("entry.self_update denied alice", "entry.delete denied alice", "entry.update denied alice");
    assertThat(trail).allSatisfy(e -> assertThat(detailKeys(e)).noneMatch(k -> k.endsWith(".new")));
    assertThat(trail(ALICE)).extracting(e -> e.get("action").asString() + " " + e.get("outcome").asString() + " " + e.get("actor").get("id").asString())
        .containsExactly("entry.update denied admin", "entry.update denied bob");
  }

  @Test
  void selfServiceWritesOnlySelfEditableFieldsOfTheUsersOwnEntry() throws Exception {
    DirectoryEntry before = read(ALICE);
    Client alice = login("alice");
    Map<String, Object> values = new HashMap<>();
    values.put("mobile", List.of("+1 555 0199"));
    values.put("title", "CTO"); // not self-editable
    values.put("mail", "ceo@example.com"); // not self-editable
    values.put("acmeBadgeNumber", "0"); // read-only
    MvcResult r = alice.put("/api/me/def-staff/entry" + q(ALICE), Map.of("values", values));
    assertThat(status(r)).as(error(r)).isEqualTo(200);

    assertThat(modifies).containsExactly(ALICE + ": REPLACE mobile [+1 555 0199]");
    DirectoryEntry after = read(ALICE);
    assertThat(after.values("mobile")).containsExactly("+1 555 0199");
    assertThat(without(after, "mobile")).isEqualTo(without(before, "mobile"));

    JsonNode event = trail(ALICE).get(0);
    assertThat(event.get("action").asString()).isEqualTo("entry.self_update");
    assertThat(event.get("outcome").asString()).isEqualTo("success");
    assertThat(event.get("actor").get("id").asString()).isEqualTo("alice");
    assertThat(event.get("target").get("id").asString()).isEqualTo(ALICE);
    assertThat(event.get("target").get("name").asString()).isEqualTo("Alice Anderson");
    assertThat(detail(event, "mobile.old")).isNull();
    assertThat(detail(event, "mobile.new")).isEqualTo("+1 555 0199");
    assertThat(detailKeys(event)).noneMatch(k -> k.startsWith("title.") || k.startsWith("mail.") || k.startsWith("acmeBadgeNumber."));
  }

  @Test
  void renamingAndChangingInOneSaveMovesTheEntryWithEverythingItHeld() throws Exception {
    DirectoryEntry before = read(BOB);
    Client admin = login("admin");
    MvcResult r = admin.put("/api/directories/staff/entry" + q(BOB), Map.of("values", Map.of("uid", "bob.baker", "title", "Head of Sales")));
    assertThat(status(r)).as(error(r)).isEqualTo(200);
    String renamed = "uid=bob.baker,ou=people," + B;
    assertThat(body(r).get("dn").asString()).isEqualTo(renamed);

    assertThat(read(BOB)).isNull();
    DirectoryEntry after = read(renamed);
    assertThat(after.values("uid")).containsExactly("bob.baker");
    assertThat(after.values("title")).containsExactly("Head of Sales");
    assertThat(without(after, "uid", "title")).isEqualTo(without(before, "uid", "title"));

    // One event, found under either name.
    List<JsonNode> trail = trail(renamed);
    assertThat(trail).hasSize(1);
    assertThat(trail(BOB)).extracting(e -> e.get("id").asString()).containsExactly(trail.get(0).get("id").asString());
    JsonNode event = trail.get(0);
    assertThat(event.get("target").get("id").asString()).isEqualTo(renamed);
    assertThat(event.get("target").get("name").asString()).isEqualTo("Bob Baker");
    assertThat(detail(event, "previousDn")).isEqualTo(BOB);
    assertThat(detail(event, "uid.old")).isEqualTo("bob");
    assertThat(detail(event, "uid.new")).isEqualTo("bob.baker");
    assertThat(detail(event, "title.old")).isEqualTo("Account Executive");
    assertThat(detail(event, "title.new")).isEqualTo("Head of Sales");
  }

  @Test
  void aDeleteKeepsEveryAttributeTheEntryHeldOnItsFormOrNotWithSecretsRedacted() throws Exception {
    // Bob holds attributes his form shows (title, mail, ...) and others it doesn't (employeeType,
    // roomNumber, a photo, his password).
    byte[] photo = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F'};
    directory.modify(BOB, List.of(new Modification(com.unboundid.ldap.sdk.ModificationType.REPLACE, "jpegPhoto", photo),
        new Modification(com.unboundid.ldap.sdk.ModificationType.REPLACE, "roomNumber", "4-117"),
        new Modification(com.unboundid.ldap.sdk.ModificationType.REPLACE, "userPassword", "Bobs-Old-Secret-42")));
    DirectoryEntry before = read(BOB);
    assertThat(before.attributes()).containsKeys("employeetype", "roomnumber", "jpegphoto", "userpassword");

    MvcResult r = login("admin").del("/api/directories/staff/entry" + q(BOB));
    assertThat(status(r)).as(error(r)).isEqualTo(204);
    assertThat(read(BOB)).isNull();

    List<JsonNode> trail = trail(BOB);
    assertThat(trail).hasSize(1);
    JsonNode event = trail.get(0);
    assertThat(event.get("action").asString()).isEqualTo("entry.delete");
    assertThat(event.get("outcome").asString()).isEqualTo("success");
    assertThat(event.get("target").get("name").asString()).isEqualTo("Bob Baker");
    // Every attribute, each as <attribute>.old with nothing new.
    for (String attribute : before.attributes().keySet()) {
      assertThat(detailKeys(event)).as(attribute).contains(attribute + ".old", attribute + ".new");
      assertThat(event.get("details").get(attribute + ".new").isNull()).as(attribute).isTrue();
    }
    // On the form.
    assertThat(detail(event, "title.old")).isEqualTo("Account Executive");
    assertThat(detail(event, "mail.old")).isEqualTo("bob@example.com");
    // Not on the form.
    assertThat(detail(event, "employeetype.old")).isEqualTo("Full time");
    assertThat(detail(event, "roomnumber.old")).isEqualTo("4-117");
    assertThat(detail(event, "objectclass.old")).contains("inetOrgPerson", "acmePerson");
    // Binary values as the API returns them: base64.
    assertThat(detail(event, "jpegphoto.old")).isEqualTo(java.util.Base64.getEncoder().encodeToString(photo));
    // Secrets: recorded as held, never their value.
    assertThat(detail(event, "userpassword.old")).isEqualTo("[redacted]");
    assertThat(event.toString()).doesNotContain("Bobs-Old-Secret-42");
    assertThat(java.nio.file.Files.readString(AUDIT_FILE)).doesNotContain("Bobs-Old-Secret-42");
  }

  @Test
  void theFirstChangeToAnEntryWithoutTheFormsAuxiliaryClassAddsIt() throws Exception {
    // Provisioned by another system: an inetOrgPerson without the acmePerson extension.
    String ivan = "uid=ivan,ou=people," + B;
    directory.add(ivan, Map.of("objectClass", List.of("top", "person", "organizationalPerson", "inetOrgPerson"), "uid", List.of("ivan"),
        "cn", List.of("Ivan Ivanov"), "givenName", List.of("Ivan"), "sn", List.of("Ivanov"), "mail", List.of("ivan@example.com")));
    Client admin = login("admin");

    // Saving without a change writes nothing (not even the class) and records nothing.
    assertThat(status(admin.put("/api/directories/staff/entry" + q(ivan), Map.of("values", Map.of("mail", List.of("ivan@example.com")))))).isEqualTo(200);
    assertThat(modifies).isEmpty();
    assertThat(trail(ivan)).isEmpty();

    MvcResult r = admin.put("/api/directories/staff/entry" + q(ivan), Map.of("values", Map.of("acmeSkill", List.of("LDAP"))));
    assertThat(status(r)).as(error(r)).isEqualTo(200);
    assertThat(modifies).containsExactly(ivan + ": ADD objectClass [acmePerson]", ivan + ": REPLACE acmeSkill [LDAP]");
    assertThat(read(ivan).values("objectClass")).contains("acmePerson");
    assertThat(read(ivan).values("acmeSkill")).containsExactly("LDAP");

    JsonNode event = trail(ivan).get(0);
    assertThat(event.get("target").get("name").asString()).isEqualTo("Ivan Ivanov");
    assertThat(detail(event, "objectClass.old")).doesNotContain("acmePerson");
    assertThat(detail(event, "objectClass.new")).contains("acmePerson");
    assertThat(detail(event, "acmeSkill.new")).isEqualTo("LDAP");
  }

  @Test
  void groupMembersAreChangedValueByValueNeverReplacedAndARequiredListNeverEmptied() throws Exception {
    Client admin = login("admin");

    // Through the form: the new list is sent as the members added and removed.
    MvcResult form = admin.put("/api/directories/groups/entry" + q(ENGINEERING), Map.of("values", Map.of("member", List.of(ALICE, CAROL, BOB))));
    assertThat(status(form)).as(error(form)).isEqualTo(200);
    assertThat(modifies).containsExactly(ENGINEERING + ": ADD member [" + BOB + "]", ENGINEERING + ": DELETE member [" + FATIMA + "]");
    assertThat(read(ENGINEERING).values("member")).containsExactlyInAnyOrder(ALICE, CAROL, BOB);
    JsonNode formEvent = trail(ENGINEERING).get(0);
    assertThat(formEvent.get("actor").get("id").asString()).isEqualTo("admin");
    assertThat(detail(formEvent, "member.old")).contains(FATIMA).doesNotContain(BOB);
    assertThat(detail(formEvent, "member.new")).contains(BOB).doesNotContain(FATIMA);

    // In bulk: only the values added and removed, recorded as such.
    modifies.clear();
    MvcResult bulk = admin.post("/api/directories/groups/members" + q(ENGINEERING), Map.of("add", List.of(FATIMA), "remove", List.of(CAROL)));
    assertThat(status(bulk)).as(error(bulk)).isEqualTo(200);
    assertThat(modifies).containsExactly(ENGINEERING + ": ADD member [" + FATIMA + "]", ENGINEERING + ": DELETE member [" + CAROL + "]");
    assertThat(read(ENGINEERING).values("member")).containsExactlyInAnyOrder(ALICE, BOB, FATIMA);
    JsonNode bulkEvent = trail(ENGINEERING).get(0);
    assertThat(bulkEvent.get("action").asString()).isEqualTo("entry.update");
    assertThat(detail(bulkEvent, "change")).isEqualTo("values");
    assertThat(detail(bulkEvent, "member.old")).isEqualTo(CAROL);
    assertThat(detail(bulkEvent, "member.new")).isEqualTo(FATIMA);

    // Members are required: removing a group's last member is refused, and nothing is written.
    modifies.clear();
    MvcResult last = admin.post("/api/directories/groups/members" + q(HR), Map.of("remove", List.of("uid=erin,ou=people," + B)));
    assertThat(status(last)).isEqualTo(400);
    assertThat(body(last).get("details").get("member").asString()).isEqualTo("Members is required");
    assertThat(modifies).isEmpty();
    assertThat(read(HR).values("member")).containsExactly("uid=erin,ou=people," + B);
    assertThat(trail(HR)).isEmpty();
  }

  @Test
  void loweringAGrantTakesWriteAccessAwayFromSignedInMembersAtOnce() throws Exception {
    String device = "cn=LT-0003,ou=devices," + B;
    Client dave = login("dave"); // helpdesk: write on Devices, signed in before the change
    MvcResult before = dave.put("/api/directories/devices/entry" + q(device), Map.of("values", Map.of("acmeDeviceStatus", "Retired")));
    assertThat(status(before)).as(error(before)).isEqualTo(200);

    // The administrator lowers the helpdesk grant (the page re-sends the same subject with the new level).
    Client admin = login("admin");
    MvcResult lowered = admin.post("/api/admin/grants",
        Map.of("definitionId", "def-devices", "subjectType", "group", "subject", "cn=helpdesk,ou=groups," + B, "access", "read"));
    assertThat(status(lowered)).isEqualTo(201);
    assertThat(body(lowered).get("id").asString()).isEqualTo("g-helpdesk-devices");
    long helpdeskGrants = 0;
    for (JsonNode g : body(admin.get("/api/admin/grants?definitionId=def-devices"))) {
      if (g.get("subject").asString().equalsIgnoreCase("cn=helpdesk,ou=groups," + B)) helpdeskGrants++;
    }
    assertThat(helpdeskGrants).isEqualTo(1);

    modifies.clear();
    MvcResult after = dave.put("/api/directories/devices/entry" + q(device), Map.of("values", Map.of("acmeDeviceStatus", "In use")));
    assertThat(status(after)).isEqualTo(403);
    assertThat(status(dave.get("/api/directories/devices/entry" + q(device)))).isEqualTo(200);
    assertThat(modifies).isEmpty();
    assertThat(read(device).values("acmeDeviceStatus")).containsExactly("Retired");
    assertThat(trail(device)).extracting(e -> e.get("action").asString() + " " + e.get("outcome").asString() + " " + e.get("actor").get("id").asString())
        .containsExactly("entry.update denied dave", "entry.update success dave");
  }
}
