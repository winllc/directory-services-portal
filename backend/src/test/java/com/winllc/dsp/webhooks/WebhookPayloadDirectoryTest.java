package com.winllc.dsp.webhooks;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.dsp.audit.AuditEvent;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.service.EntryChanged;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Webhook payloads name the LDAP directory of the changed entry and of the user who changed it (additive fields). */
class WebhookPayloadDirectoryTest {
  private static final String DN = "cn=devs,ou=groups,dc=example,dc=com";

  @Test
  void everyEventNamesTheDirectory() {
    SessionUser bob = new SessionUser("bob", "uid=bob,dc=example,dc=com", "Bob", null, List.of(), false, "password", null, "lab");
    EntryChanged change = new EntryChanged(EntryChanged.UPDATE, "def-lab-groups", "form-group", DN, null, "devs",
        List.of(new AuditEvent.Change("member", List.of(), List.of("uid=zoe,dc=example,dc=com"))), false, EntryChanged.Actor.of(bob, null), "lab");
    List<Map.Entry<String, Map<String, Object>>> events = WebhookService.events(change);
    assertThat(events).extracting(Map.Entry::getKey).containsExactly("entry.changed", "group.membership_changed", "form.submitted");
    assertThat(events).allSatisfy(e -> assertThat(e.getValue()).containsEntry("directory", "lab").containsEntry("definitionId", "def-lab-groups"));
    assertThat(WebhookService.actor(change)).containsEntry("username", "bob").containsEntry("directory", "lab");
  }

  @Test
  void withoutADirectoryThePayloadIsAsBefore() {
    EntryChanged change = new EntryChanged(EntryChanged.DELETE, "def-staff", "form-person", DN, null, "devs", List.of(), false,
        EntryChanged.Actor.of(null, null), null);
    assertThat(WebhookService.events(change).get(0).getValue()).doesNotContainKey("directory");
    assertThat(WebhookService.actor(change)).doesNotContainKey("directory");
  }
}
