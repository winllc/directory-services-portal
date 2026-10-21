package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.unboundid.ldif.LDIFReader;
import com.winllc.dsp.integration.IntegrationProperties;
import com.winllc.dsp.transfer.Csv;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** CSV and LDIF export and import, and bulk membership changes. */
class TransferTest extends ApiTestSupport {
  static final String ENGINEERING = "cn=engineering,ou=groups," + B;

  @Autowired IntegrationProperties props;
  @Autowired com.winllc.dsp.ldap.LdapDirectory directory;

  @AfterEach
  void limits() {
    props.getTransfer().setImportMaxRows(5000);
    props.getTransfer().setImportMaxBytes(5 * 1024 * 1024);
    props.getTransfer().setExportMaxEntries(10_000);
  }

  private MvcResult importFile(Client c, String url, String contentType, String text) throws Exception {
    return c.send(post(URI.create(url)).header("X-DSP-Request", "1").contentType(contentType).content(text.getBytes(StandardCharsets.UTF_8)));
  }

  private JsonNode imported(Client c, String url, String contentType, String text) throws Exception {
    return awaitResult(c, importFile(c, url, contentType, text));
  }

  private List<String> actions(Client admin, String action) throws Exception {
    List<String> out = new ArrayList<>();
    body(admin.get("/api/admin/audit?action=" + action + "&pageSize=200")).get("events").forEach(e -> out.add(e.get("target").get("id").asString()));
    return out;
  }

  @Test
  void exportsSearchResultsAsCsvWithAFormulaGuard() throws Exception {
    Client admin = login("admin");
    assertThat(status(admin.put("/api/directories/staff/entry" + q(ALICE), Map.of("values", Map.of("title", "=HYPERLINK(\"http://x\")"))))).isEqualTo(200);
    MvcResult r = admin.get("/api/directories/staff/export?format=csv");
    assertThat(status(r)).isEqualTo(200);
    assertThat(r.getResponse().getHeader("Content-Disposition")).startsWith("attachment").contains("staff-").contains(".csv");
    assertThat(r.getResponse().getContentType()).startsWith("text/csv");
    String csv = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(csv).startsWith(Csv.BOM + "dn,uid,");
    assertThat(csv).doesNotContainIgnoringCase("userPassword").doesNotContain("\n=").doesNotContain(",=");
    assertThat(csv).contains("\"'=HYPERLINK(\"\"http://x\"\")\"");
    int rows = csv.split("\r\n").length - 1;
    assertThat(Integer.parseInt(r.getResponse().getHeader("X-DSP-Export-Count"))).isEqualTo(rows).isGreaterThan(3);
    // A search narrows the export.
    String one = admin.get("/api/directories/staff/export?q=alice").getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(one.split("\r\n")).hasSize(2);
    assertThat(actions(admin, "data.exported")).contains("def-staff");
    // Readers export; people without access don't.
    Client alice = login("alice");
    assertThat(status(alice.get("/api/directories/devices/export"))).isEqualTo(200);
    assertThat(status(alice.get("/api/directories/partners/export"))).isEqualTo(403);
  }

  @Test
  void exportsLdifThatReadsBack() throws Exception {
    Client admin = login("admin");
    MvcResult r = admin.get("/api/directories/devices/export?format=ldif");
    assertThat(r.getResponse().getHeader("Content-Disposition")).contains(".ldif");
    String ldif = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    int count = 0;
    try (LDIFReader reader = new LDIFReader(new ByteArrayInputStream(ldif.getBytes(StandardCharsets.UTF_8)))) {
      for (var e = reader.readEntry(); e != null; e = reader.readEntry()) {
        assertThat(e.getDN()).endsWith("ou=devices," + B);
        assertThat(e.hasAttribute("objectClass")).isTrue();
        count++;
      }
    }
    assertThat(count).isEqualTo(Integer.parseInt(r.getResponse().getHeader("X-DSP-Export-Count"))).isPositive();
  }

  @Test
  void importsCsvWithADryRunFirst() throws Exception {
    Client admin = login("admin");
    String csv = admin.get("/api/directories/devices/export").getResponse().getContentAsString(StandardCharsets.UTF_8);
    List<List<String>> lines = new ArrayList<>();
    Csv.parse(csv, 1000, new boolean[1]).forEach(rec -> lines.add(new ArrayList<>(rec.cells())));
    // Change one device's asset tag, add a new device (no dn: named by its cn), and a bad row.
    List<String> cols = lines.get(0);
    lines.get(1).set(cols.indexOf("acmeAssetTag"), "TAG-CHANGED");
    List<String> fresh = new ArrayList<>(java.util.Collections.nCopies(cols.size(), ""));
    fresh.set(cols.indexOf("cn"), "LT-7777");
    fresh.set(cols.indexOf("acmeDeviceSerial"), "SN-7777");
    fresh.set(cols.indexOf("acmeDeviceType"), "Laptop");
    fresh.set(cols.indexOf("acmeDeviceStatus"), "In stock");
    lines.add(fresh);
    List<String> bad = new ArrayList<>(fresh);
    bad.set(cols.indexOf("cn"), "LT-8888");
    bad.set(cols.indexOf("acmeDeviceType"), "Toaster");
    lines.add(bad);
    StringBuilder out = new StringBuilder();
    lines.forEach(l -> Csv.row(out, l));
    String file = out.toString();

    int importsBefore = actions(admin, "data.imported").size();
    JsonNode dry = imported(admin, "/api/directories/devices/import?dryRun=true", "text/csv", file);
    assertThat(dry.get("dryRun").asBoolean()).isTrue();
    assertThat(dry.get("created").asInt()).isEqualTo(1);
    assertThat(dry.get("updated").asInt()).isEqualTo(1);
    assertThat(dry.get("failed").asInt()).isEqualTo(1);
    assertThat(dry.get("unchanged").asInt()).isEqualTo(lines.size() - 4);
    JsonNode badRow = dry.get("rows").get(lines.size() - 2);
    assertThat(badRow.get("operation").asString()).isEqualTo("error");
    assertThat(badRow.get("details").toString()).contains("Toaster");
    assertThat(dry.get("rows").get(0).get("attributes").toString()).contains("acmeAssetTag");
    // Nothing was written.
    assertThat(status(admin.get("/api/directories/devices/entry" + q("cn=LT-7777,ou=devices," + B)))).isEqualTo(404);
    assertThat(actions(admin, "data.imported")).hasSize(importsBefore);

    JsonNode run = imported(admin, "/api/directories/devices/import", "text/csv", file);
    assertThat(run.get("created").asInt()).isEqualTo(1);
    assertThat(run.get("updated").asInt()).isEqualTo(1);
    assertThat(status(admin.get("/api/directories/devices/entry" + q("cn=LT-7777,ou=devices," + B)))).isEqualTo(200);
    String changedDn = run.get("rows").get(0).get("dn").asString();
    JsonNode changed = body(admin.get("/api/directories/devices/entry" + q(changedDn)));
    assertThat(changed.get("attributes").get("acmeassettag").get(0).asString()).isEqualTo("TAG-CHANGED");
    // Audited per entry, and the import as a whole.
    assertThat(actions(admin, "entry.create")).contains("cn=LT-7777,ou=devices," + B);
    assertThat(actions(admin, "entry.update")).contains(changedDn);
    assertThat(actions(admin, "data.imported")).hasSize(importsBefore + 1).first().isEqualTo("def-devices");
    // An exported file imports unchanged.
    String again = admin.get("/api/directories/devices/export").getResponse().getContentAsString(StandardCharsets.UTF_8);
    JsonNode same = imported(admin, "/api/directories/devices/import?dryRun=true", "text/csv", again);
    assertThat(same.get("unchanged").asInt()).isEqualTo(same.get("total").asInt());
  }

  @Test
  void refusesUnknownColumnsOversizedFilesAndReaders() throws Exception {
    Client admin = login("admin");
    MvcResult unknown = importFile(admin, "/api/directories/devices/import", "text/csv", "cn,shoeSize\r\nLT-1,44\r\n");
    assertThat(status(unknown)).isEqualTo(400);
    assertThat(body(unknown).get("error").asString()).contains("shoeSize");

    props.getTransfer().setImportMaxRows(1);
    assertThat(status(importFile(admin, "/api/directories/devices/import", "text/csv", "cn\r\nA\r\nB\r\n"))).isEqualTo(400);
    props.getTransfer().setImportMaxBytes(10);
    assertThat(status(importFile(admin, "/api/directories/devices/import", "text/csv", "cn\r\nLT-123456789\r\n"))).isEqualTo(413);
    props.getTransfer().setImportMaxBytes(5 * 1024 * 1024);

    // alice may read devices, not write them.
    Client alice = login("alice");
    assertThat(status(importFile(alice, "/api/directories/devices/import?dryRun=true", "text/csv", "cn\r\nLT-1\r\n"))).isEqualTo(403);
    // The CSRF header is required.
    MvcResult noHeader = mvc.perform(post("/api/directories/devices/import").session(admin.session).contentType("text/csv").content("cn\r\nX\r\n"))
        .andReturn();
    assertThat(status(noHeader)).isEqualTo(403);
  }

  @Test
  void importsLdifEntriesAndRefusesChangeRecords() throws Exception {
    Client admin = login("admin");
    String ldif = """
        dn: cn=LT-5555,ou=devices,dc=example,dc=com
        objectClass: top
        objectClass: acmeDevice
        cn: LT-5555
        acmeDeviceSerial: SN-5555
        acmeDeviceType: Laptop
        acmeDeviceStatus: In stock
        createTimestamp: 20260101000000Z

        dn: cn=LT-0001,ou=devices,dc=example,dc=com
        changetype: modify
        replace: acmeAssetTag
        acmeAssetTag: X
        -

        dn: cn=wrong,ou=elsewhere,dc=example,dc=com
        cn: wrong
        """;
    JsonNode r = imported(admin, "/api/directories/devices/import?format=ldif", "text/plain", ldif);
    assertThat(r.get("created").asInt()).isEqualTo(1);
    assertThat(r.get("failed").asInt()).isEqualTo(2);
    assertThat(r.get("rows").get(0).get("ignored").toString()).contains("createTimestamp");
    assertThat(r.get("rows").get(1).get("error").asString()).contains("changetype: add");
    assertThat(r.get("rows").get(2).get("operation").asString()).isEqualTo("error");
    assertThat(status(admin.get("/api/directories/devices/entry" + q("cn=LT-5555,ou=devices," + B)))).isEqualTo(200);
  }

  @Test
  void refusesLdifValuesGivenAsUrls() throws Exception {
    Client admin = login("admin");
    int importsBefore = actions(admin, "data.imported").size();
    java.nio.file.Path secret = java.nio.file.Files.createTempFile("dsp-secret", ".txt");
    java.nio.file.Files.writeString(secret, "TOP-SECRET-CONTENT");
    for (String value : List.of("description:< " + secret.toUri(), "description:\n < " + secret.toUri(), "description:< https://example.com/x")) {
      String ldif = "dn: cn=LT-6666,ou=devices,dc=example,dc=com\ncn: LT-6666\nacmeDeviceSerial: SN-6666\nacmeDeviceType: Laptop\n"
          + "acmeDeviceStatus: In stock\n" + value + "\n";
      MvcResult r = importFile(admin, "/api/directories/devices/import?format=ldif&dryRun=true", "text/plain", ldif);
      assertThat(status(r)).as(value).isEqualTo(400);
      assertThat(body(r).get("error").asString()).contains("URL");
      MvcResult run = importFile(admin, "/api/directories/devices/import?format=ldif", "text/plain", ldif);
      assertThat(status(run)).isEqualTo(400);
    }
    assertThat(status(admin.get("/api/directories/devices/entry" + q("cn=LT-6666,ou=devices," + B)))).isEqualTo(404);
    assertThat(actions(admin, "data.imported")).hasSize(importsBefore);
  }

  @Test
  void changesMembersOfALargeGroupValueByValue() throws Exception {
    Client admin = login("admin");
    // A group of 2,000 members (more than Active Directory returns without ranges).
    List<String> many = new ArrayList<>();
    for (int i = 0; i < 2000; i++) many.add("uid=user" + i + ",ou=people," + B);
    many.add(ALICE);
    directory.modify(ENGINEERING, List.of(new com.unboundid.ldap.sdk.Modification(com.unboundid.ldap.sdk.ModificationType.REPLACE, "member",
        many.toArray(String[]::new))));
    MvcResult r = admin.post("/api/directories/groups/members" + q(ENGINEERING), Map.of("add", List.of("uid=bob,ou=people," + B), "remove", List.of(ALICE)));
    assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
    assertThat(body(r).get("added").size()).isEqualTo(1);
    assertThat(body(r).get("removed").size()).isEqualTo(1);
    List<String> after = directory.get(ENGINEERING, List.of("member")).values("member");
    assertThat(after).hasSize(2001).contains("uid=bob,ou=people," + B).doesNotContain(ALICE).contains("uid=user1999,ou=people," + B);
    // Audited as values added and removed, not the whole list.
    JsonNode event = body(admin.get("/api/admin/audit?action=entry.update&pageSize=5")).get("events").get(0);
    assertThat(event.get("details").get("change").asString()).isEqualTo("values");
    assertThat(event.get("details").get("member.new").asString()).isEqualTo("uid=bob,ou=people," + B);
  }

  /** Returns engineering's members in ranges of two, as Active Directory does past 1,500 values. */
  private void rangedEngineering() {
    embedded.intercept(new com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor() {
      @Override
      public void processSearchEntry(com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSearchEntry entry) {
        var e = entry.getSearchEntry();
        if (!com.winllc.dsp.ldap.Dns.equal(e.getDN(), ENGINEERING) || !e.hasAttribute("member")) return;
        String[] values = e.getAttributeValues("member");
        com.unboundid.ldap.sdk.Entry copy = e.duplicate();
        copy.removeAttribute("member");
        copy.addAttribute("member;range=0-1", java.util.Arrays.copyOf(values, Math.min(2, values.length)));
        entry.setSearchEntry(copy);
      }
    });
  }

  @Test
  void editingAGroupInTheFormSendsOnlyTheMembersThatChanged() throws Exception {
    Client admin = login("admin");
    List<com.unboundid.ldap.sdk.Modification> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
    embedded.intercept(new com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor() {
      @Override
      public void processModifyRequest(com.unboundid.ldap.listener.interceptor.InMemoryInterceptedModifyRequest request) {
        if (com.winllc.dsp.ldap.Dns.equal(request.getRequest().getDN(), ENGINEERING)) seen.addAll(request.getRequest().getModifications());
      }
    });
    String bob = "uid=bob,ou=people," + B;
    String carol = "uid=carol,ou=people," + B;
    MvcResult r = admin.put("/api/directories/groups/entry" + q(ENGINEERING), Map.of("values", Map.of("member", List.of(ALICE, carol, bob))));
    assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
    assertThat(seen).extracting(m -> m.getModificationType()).containsExactlyInAnyOrder(com.unboundid.ldap.sdk.ModificationType.ADD,
        com.unboundid.ldap.sdk.ModificationType.DELETE);
    assertThat(seen).allMatch(m -> m.getAttributeName().equals("member"));
    assertThat(seen.stream().filter(m -> m.getModificationType() == com.unboundid.ldap.sdk.ModificationType.ADD).findFirst().orElseThrow().getValues())
        .containsExactly(bob);
    assertThat(seen.stream().filter(m -> m.getModificationType() == com.unboundid.ldap.sdk.ModificationType.DELETE).findFirst().orElseThrow().getValues())
        .containsExactly("uid=fatima,ou=people," + B);
  }

  @Test
  void aGroupReadInRangesIsNeverReplacedByTheFormOrAnImport() throws Exception {
    Client admin = login("admin");
    List<String> before = directory.get(ENGINEERING, List.of("member")).values("member");
    rangedEngineering();
    String bob = "uid=bob,ou=people," + B;
    MvcResult form = admin.put("/api/directories/groups/entry" + q(ENGINEERING), Map.of("values", Map.of("member", List.of(bob))));
    assertThat(status(form)).isEqualTo(409);
    assertThat(body(form).get("code").asString()).isEqualTo("ranged_values");
    StringBuilder csv = new StringBuilder();
    Csv.row(csv, List.of("dn", "member"));
    Csv.row(csv, List.of(ENGINEERING, bob));
    JsonNode run = imported(admin, "/api/directories/groups/import", "text/csv", csv.toString());
    assertThat(run.get("failed").asInt()).isEqualTo(1);
    assertThat(run.get("rows").get(0).get("error").asString()).contains("too many values");
    // The bulk change still works: it sends just the new value.
    MvcResult bulk = admin.post("/api/directories/groups/members" + q(ENGINEERING), Map.of("add", List.of(bob)));
    assertThat(status(bulk)).as(bulk.getResponse().getContentAsString()).isEqualTo(200);
    assertThat(body(bulk).get("partial").asBoolean()).isTrue();
    embedded.intercept(null);
    assertThat(directory.get(ENGINEERING, List.of("member")).values("member")).containsAll(before).contains(bob).hasSize(before.size() + 1);
  }

  @Test
  void changesAndExportsGroupMembersInBulk() throws Exception {
    Client admin = login("admin");
    List<String> add = List.of("uid=bob,ou=people," + B, "uid=dave,ou=people," + B, "uid=erin,ou=people," + B);
    MvcResult r = admin.post("/api/directories/groups/members" + q(ENGINEERING), Map.of("add", add, "remove", List.of(ALICE)));
    assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
    assertThat(body(r).get("added").size()).isEqualTo(3);
    assertThat(body(r).get("removed").size()).isEqualTo(1);
    // One audited change of the group.
    assertThat(actions(admin, "entry.update")).containsOnlyOnce(ENGINEERING);

    String csv = admin.get("/api/directories/groups/members/export" + q(ENGINEERING)).getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(csv).startsWith(Csv.BOM + "member\r\n").contains("\"uid=bob,ou=people,dc=example,dc=com\"").doesNotContain(ALICE);
    String ldif = admin.get("/api/directories/groups/members/export" + q(ENGINEERING) + "&format=ldif").getResponse().getContentAsString();
    assertThat(ldif).contains("dn: " + ENGINEERING).contains("member: uid=bob");

    // A reader can't change members.
    Client alice = login("alice");
    assertThat(status(alice.post("/api/directories/groups/members" + q(ENGINEERING), Map.of("add", List.of(ALICE))))).isEqualTo(403);
    props.getTransfer().setBulkMaxMembers(2);
    try {
      assertThat(status(admin.post("/api/directories/groups/members" + q(ENGINEERING), Map.of("add", add)))).isEqualTo(400);
    } finally {
      props.getTransfer().setBulkMaxMembers(5000);
    }
  }

  @Test
  void csvCellsRoundTrip() {
    for (String v : List.of("plain", "=1+1", "+44 20 7946 0000", "-5", "@user", "a,b", "say \"hi\"", "two\nlines", "'quoted", " =1+1",
        "\t=cmd", "\n@x", "  -2+3")) {
      StringBuilder sb = new StringBuilder();
      Csv.row(sb, List.of(v));
      boolean[] over = {false};
      String parsed = Csv.parse(sb.toString(), 10, over).get(0).cells().get(0);
      assertThat(Csv.unguard(parsed)).as(v).isEqualTo(v);
      if (v.strip().startsWith("=") || v.startsWith("+") || v.strip().startsWith("@") || v.startsWith("\t") || v.equals("  -2+3")) {
        assertThat(parsed).as(v).startsWith("'");
      }
    }
    assertThat(Csv.cell("-5")).isEqualTo("-5");
    // Only an apostrophe the guard could have added is removed.
    assertThat(Csv.unguard("'plain")).isEqualTo("'plain");
    assertThat(Csv.unguard("'=x")).isEqualTo("=x");
    List<String> values = List.of("a|b", "c\\d", "e");
    assertThat(Csv.split(Csv.join(values))).isEqualTo(values);
  }
}
