package com.winllc.dsp.service;

import com.winllc.dsp.integration.IntegrationProperties;
import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.model.DirectoryEntry;
import com.winllc.dsp.model.FormDefinition;
import com.winllc.dsp.model.FormField;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Group membership, as the portal already manages it: a multi-valued field of a group's form
 * (member, uniqueMember or memberUid, or any multi-valued DN field). Reads and changes go through
 * {@link EntryService}, so the definition's namespace, the user's permissions, the form's
 * validation and the audit trail all apply exactly as when the group is edited in the portal.
 */
@Service
public class Memberships {
  /** Attributes that hold group members, in the order a default is picked. */
  public static final List<String> MEMBER_ATTRIBUTES = List.of("member", "uniquemember", "memberuid");
  private static final Set<String> DN_VALUED = Set.of("member", "uniquemember");

  public record Members(String dn, String attribute, List<String> members) {}

  public record Change(String dn, String attribute, List<String> added, List<String> removed, List<String> members) {}

  private final EntryService entries;
  private final IntegrationProperties props;

  public Memberships(EntryService entries, IntegrationProperties props) {
    this.entries = entries;
    this.props = props;
  }

  /**
   * The form field holding members: the one named, which must be a multi-valued field of the form,
   * or the first of member, uniqueMember, memberUid the form has.
   */
  public FormField field(FormDefinition form, String attribute) {
    if (attribute != null && !attribute.isBlank()) {
      FormField f = form.fields().stream().filter(x -> x.attribute().equalsIgnoreCase(attribute.trim())).findFirst()
          .orElseThrow(() -> ApiException.badRequest("This directory's form has no field \"" + attribute.trim() + "\""));
      boolean membership = MEMBER_ATTRIBUTES.contains(f.attribute().toLowerCase(Locale.ROOT)) || "dn".equals(f.format());
      if (!f.multiValued() || !membership) {
        throw ApiException.badRequest("\"" + f.attribute() + "\" is not a membership field (a multi-valued member, uniqueMember, memberUid or DN field)");
      }
      return f;
    }
    for (String a : MEMBER_ATTRIBUTES) {
      for (FormField f : form.fields()) if (f.multiValued() && f.attribute().equalsIgnoreCase(a)) return f;
    }
    throw ApiException.badRequest("This directory's form has no member field (member, uniqueMember or memberUid); name one with \"attribute\"");
  }

  public Members read(SessionUser user, DirectoryDefinition def, String dn, String attribute) {
    FormField f = field(entries.form(def), attribute);
    DirectoryEntry entry = entries.get(user, def, dn);
    return new Members(entry.dn(), f.attribute(), entry.values(f.attribute()));
  }

  /** Adds and removes members in one change of the entry (nothing is written when nothing changes). */
  public Change change(SessionUser user, DirectoryDefinition def, String dn, String attribute, List<String> add, List<String> remove) {
    List<String> toAdd = clean(add);
    List<String> toRemove = clean(remove);
    int max = props.getTransfer().getBulkMaxMembers();
    if (toAdd.size() + toRemove.size() > max) throw ApiException.badRequest("At most " + max + " members can be added or removed at once");
    if (toAdd.isEmpty() && toRemove.isEmpty()) throw ApiException.badRequest("Nothing to add or remove");
    FormField f = field(entries.form(def), attribute);
    boolean dnValued = DN_VALUED.contains(f.attribute().toLowerCase(Locale.ROOT)) || "dn".equals(f.format());
    if (dnValued) {
      for (String v : toAdd) if (v.length() > 1024 || !Dns.isValid(v)) throw ApiException.badRequest("\"" + shorten(v) + "\" is not a valid DN");
    }
    DirectoryEntry entry = entries.get(user, def, dn);
    List<String> current = entry.values(f.attribute());
    List<String> next = new ArrayList<>();
    List<String> removed = new ArrayList<>();
    for (String v : current) {
      if (toRemove.stream().anyMatch(r -> same(r, v, dnValued))) removed.add(v);
      else next.add(v);
    }
    List<String> added = new ArrayList<>();
    for (String v : toAdd) {
      if (next.stream().noneMatch(x -> same(x, v, dnValued))) {
        next.add(v);
        added.add(v);
      }
    }
    if (added.isEmpty() && removed.isEmpty()) return new Change(entry.dn(), f.attribute(), List.of(), List.of(), current);
    DirectoryEntry updated = entries.update(user, def, entry.dn(), Map.of(f.attribute(), next), false);
    return new Change(updated.dn(), f.attribute(), added, removed, updated.values(f.attribute()));
  }

  private static boolean same(String a, String b, boolean dn) {
    return dn ? Dns.equal(a, b) : a.equalsIgnoreCase(b);
  }

  private static List<String> clean(List<String> values) {
    if (values == null) return List.of();
    List<String> out = new ArrayList<>();
    for (String v : values) {
      if (v == null) continue;
      String s = v.trim();
      if (!s.isEmpty() && !out.contains(s)) out.add(s);
    }
    return out;
  }

  private static String shorten(String s) {
    return s.length() > 80 ? s.substring(0, 80) + "..." : s;
  }
}
