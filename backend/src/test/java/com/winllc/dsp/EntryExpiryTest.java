package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;

import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.winllc.dsp.expiry.EntryExpiryService;
import com.winllc.dsp.ldap.EmbeddedDirectory;
import com.winllc.dsp.model.FormDefinition;
import com.winllc.dsp.model.FormField;
import com.winllc.dsp.service.Mailer;
import java.net.URI;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Entry expiry: a date field, the sponsor told before the end date (mail, the entry.expiring
 * event, the audit), and the sponsor extending the entry within the definition's limit.
 */
class EntryExpiryTest extends ApiTestSupport {
  static final String CAROL = "uid=carol,ou=people," + B;
  static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

  @Autowired EntryExpiryService expiry;
  @Autowired Mailer mailer;
  @Autowired com.winllc.dsp.service.LoginThrottle throttle;

  @org.junit.jupiter.api.BeforeEach
  void noLockouts() {
    // Other tests lock users out on purpose (AuditLogTest: carol); a negative window drops every count.
    throttle.sweep(-1);
  }

  @AfterEach
  void realClock() {
    expiry.useClock(Clock.systemUTC());
  }

  /** The person form gains an end date (carLicense here: any string attribute). */
  void addEndDate() {
    store.update(d -> {
      List<FormDefinition> forms = new ArrayList<>();
      for (FormDefinition f : d.forms) {
        if (!f.id().equals("form-person")) {
          forms.add(f);
          continue;
        }
        List<FormField> fields = new ArrayList<>(f.fields());
        fields.add(new FormField("f-end", "carLicense", "Ends on", null, "text", false, false, false, false, "date", null, null, null, null,
            null, "Organization", null));
        forms.add(new FormDefinition(f.id(), f.name(), f.description(), f.objectClasses(), f.rdnAttribute(), fields, f.createdAt(),
            f.updatedAt()));
      }
      d.forms = forms;
      return null;
    });
  }

  Map<String, Object> staffInput() throws Exception {
    JsonNode d = body(login("admin").get("/api/admin/definitions")).valueStream().filter(x -> x.get("id").asString().equals("def-staff"))
        .findFirst().orElseThrow();
    @SuppressWarnings("unchecked")
    Map<String, Object> in = new HashMap<>(mapper.convertValue(d, Map.class));
    in.keySet().removeAll(List.of("id", "createdAt", "updatedAt"));
    return in;
  }

  void setAlice(String end) throws Exception {
    URI url = URI.create(embedded.url());
    try (LDAPConnection c = new LDAPConnection(url.getHost(), url.getPort(), EmbeddedDirectory.SERVICE_DN, EmbeddedDirectory.SERVICE_PASSWORD)) {
      c.modify(ALICE, new Modification(ModificationType.REPLACE, "carLicense", end),
          new Modification(ModificationType.REPLACE, "manager", CAROL));
    }
  }

  @Test
  void sponsorsAreToldAndExtendTheirEntriesWithinTheLimit() throws Exception {
    addEndDate();
    Client admin = login("admin");
    // The date field must be a date, the sponsor a DN field.
    Map<String, Object> def = staffInput();
    def.put("expiry", Map.of("dateAttribute", "title", "sponsorAttribute", "manager"));
    assertThat(status(admin.put("/api/admin/definitions/def-staff", def))).isEqualTo(400);
    def.put("expiry", Map.of("dateAttribute", "carLicense", "sponsorAttribute", "manager", "warnDays", 7, "maxExtensionDays", 30));
    MvcResult saved = admin.put("/api/admin/definitions/def-staff", def);
    assertThat(status(saved)).as(saved.getResponse().getContentAsString()).isEqualTo(200);

    expiry.useClock(Clock.fixed(Instant.parse("2026-11-25T08:00:00Z"), ZoneOffset.UTC));
    setAlice(TODAY.plusDays(3).toString());

    mailer.clear();
    EntryExpiryService.Run run = expiry.run();
    assertThat(run.notified()).isEqualTo(1);
    assertThat(mailer.recent()).hasSize(1);
    Mailer.Sent sent = mailer.recent().get(0);
    assertThat(sent.to()).containsExactly("carol@example.com");
    assertThat(sent.subject()).contains("in 3 days");
    assertThat(Files.readString(AUDIT_FILE)).contains("\"action\":\"entry.expiring\"");
    // Ten days away: not yet.
    setAlice(TODAY.plusDays(10).toString());
    assertThat(expiry.run().notified()).isZero();
    setAlice(TODAY.plusDays(3).toString());

    // Carol sponsors Alice; Alice sponsors nobody.
    Client carol = login("carol");
    JsonNode mine = body(carol.get("/api/sponsored"));
    assertThat(mine).hasSize(1);
    assertThat(mine.get(0).get("dn").asString()).isEqualToIgnoringCase(ALICE);
    assertThat(mine.get(0).get("daysLeft").asLong()).isEqualTo(3);
    assertThat(body(login("alice").get("/api/sponsored"))).isEmpty();

    Map<String, Object> renew = new HashMap<>(Map.of("definitionId", "def-staff", "dn", ALICE));
    renew.put("date", TODAY.plusDays(31).toString());
    assertThat(status(carol.post("/api/sponsored/renew", renew))).as("past the limit").isEqualTo(400);
    renew.put("date", TODAY.plusDays(1).toString());
    assertThat(status(carol.post("/api/sponsored/renew", renew))).as("never shortened").isEqualTo(409);
    renew.put("date", TODAY.plusDays(30).toString());
    assertThat(status(login("alice").post("/api/sponsored/renew", renew))).as("not the sponsor").isEqualTo(403);
    MvcResult ok = carol.post("/api/sponsored/renew", renew);
    assertThat(status(ok)).as(ok.getResponse().getContentAsString()).isEqualTo(200);
    assertThat(body(ok).get("endDate").asString()).isEqualTo(TODAY.plusDays(30).toString());
    JsonNode entry = body(admin.get("/api/directories/def-staff/entry" + q(ALICE)));
    assertThat(entry.toString()).contains(TODAY.plusDays(30).toString());
    assertThat(Files.readString(AUDIT_FILE)).contains("\"via\":\"sponsor-renewal\"");
    // Thirty days away now: no notice.
    mailer.clear();
    assertThat(expiry.run().notified()).isZero();
  }
}
