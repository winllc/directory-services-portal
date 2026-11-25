package com.winllc.dsp.groups;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;
import com.winllc.dsp.audit.AuditJson;
import com.winllc.dsp.cluster.Cluster;
import com.winllc.dsp.cluster.SharedJsonList;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.util.LogSafe;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The end dates of the memberships the portal granted for a limited time: one record per
 * (definition, group, member). Alone they are kept in {@value #FILE} in the data directory; with
 * the shared database in {@code dsp_group_membership_expiry} (Flyway V13), where a file written
 * before the database was configured is imported once. Members without a record have no end date
 * as far as the portal knows (added elsewhere, or before there were end dates).
 */
@Component
public class MembershipExpiries {
  private static final Logger log = LoggerFactory.getLogger(MembershipExpiries.class);
  static final String FILE = "group-membership-expiries.json";
  /** The shared document that says the file was imported into the table. */
  static final String IMPORTED = "group-membership-expiries-imported";
  private static final String COLUMNS = "definition_id, group_dn, group_name, member, expires_on, granted_by, granted_at, last_attempt";

  /**
   * {@code member} (the value the group's member attribute holds) belongs to {@code groupDn} until
   * the end of {@code until} (a day, UTC, as {@code yyyy-MM-dd}). {@code grantedBy}: who set the
   * date; {@code lastAttempt}: when removing the member last failed.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Expiry(String definitionId, String groupDn, String groupName, String member, String until, String grantedBy, String grantedAt,
      String lastAttempt) {

    public LocalDate untilDate() {
      return LocalDate.parse(until);
    }

    boolean same(String definition, String group, String value) {
      return definitionId.equals(definition) && normal(groupDn).equals(normal(group)) && normal(member).equals(normal(value));
    }

    Expiry attempted(String at) {
      return new Expiry(definitionId, groupDn, groupName, member, until, grantedBy, grantedAt, at);
    }
  }

  private final JdbcTemplate jdbc;
  private final SharedJsonList<Expiry> file;

  public MembershipExpiries(PortalProperties props, JsonMapper mapper, Cluster cluster) {
    Path path = Path.of(props.getDataDir()).toAbsolutePath().resolve(FILE);
    if (cluster.shared()) {
      this.jdbc = cluster.jdbc();
      this.file = null;
      importFile(path, mapper, cluster);
    } else {
      this.jdbc = null;
      this.file = new SharedJsonList<>("group-membership-expiries", path, mapper, cluster, new TypeReference<List<Expiry>>() {});
    }
  }

  /** A DN in its normalized form; another value (a login name) in lower case. */
  static String normal(String value) {
    try {
      return new DN(value).toNormalizedString();
    } catch (LDAPException e) {
      return value.toLowerCase(Locale.ROOT);
    }
  }

  private static String groupKey(String definitionId, String groupDn) {
    return AuditJson.sha256Hex(definitionId + "\n" + normal(groupDn));
  }

  private static String memberKey(String definitionId, String member) {
    return AuditJson.sha256Hex(definitionId + "\n" + normal(member));
  }

  private static String id(String definitionId, String groupDn, String member) {
    return AuditJson.sha256Hex(definitionId + "\n" + normal(groupDn) + "\n" + normal(member));
  }

  private static Timestamp ts(String instant) {
    return instant == null ? null : Timestamp.from(Instant.parse(instant));
  }

  private static String instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant().truncatedTo(ChronoUnit.MILLIS).toString();
  }

  private static Expiry row(ResultSet rs, int i) throws SQLException {
    return new Expiry(rs.getString("definition_id"), rs.getString("group_dn"), rs.getString("group_name"), rs.getString("member"),
        rs.getDate("expires_on").toLocalDate().toString(), rs.getString("granted_by"), instant(rs, "granted_at"), instant(rs, "last_attempt"));
  }

  private void insert(Expiry e, String onConflict) {
    jdbc.update("INSERT INTO dsp_group_membership_expiry (id, group_key, member_key, " + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
        + "ON CONFLICT (id) " + onConflict,
        id(e.definitionId(), e.groupDn(), e.member()), groupKey(e.definitionId(), e.groupDn()), memberKey(e.definitionId(), e.member()),
        e.definitionId(), e.groupDn(), e.groupName(), e.member(), Date.valueOf(e.untilDate()), e.grantedBy(), ts(e.grantedAt()),
        ts(e.lastAttempt()));
  }

  /** Imports the file of an installation that ran alone before, once (recorded in a shared document). */
  private void importFile(Path path, JsonMapper mapper, Cluster cluster) {
    try {
      if (!Files.exists(path) || cluster.documents().get(IMPORTED).exists()) return;
      List<Expiry> stored = mapper.readValue(Files.readString(path, StandardCharsets.UTF_8), new TypeReference<List<Expiry>>() {});
      // Another replica importing at the same time writes the same rows.
      for (Expiry e : stored) insert(e, "DO NOTHING");
      cluster.documents().createIfAbsent(IMPORTED, "{\"records\":" + stored.size() + "}");
      log.info("Imported {} into the shared database", path);
    } catch (IOException | RuntimeException e) {
      log.error("Could not import {}: {}", path, LogSafe.of(e.getMessage()));
    }
  }

  private void change(Consumer<List<Expiry>> change) {
    try {
      file.change(change);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Records (or replaces) the end dates of memberships. */
  public void set(List<Expiry> records) {
    if (records.isEmpty()) return;
    if (jdbc != null) {
      for (Expiry e : records) {
        insert(e, "DO UPDATE SET group_dn = EXCLUDED.group_dn, group_name = EXCLUDED.group_name, member = EXCLUDED.member, "
            + "expires_on = EXCLUDED.expires_on, granted_by = EXCLUDED.granted_by, granted_at = EXCLUDED.granted_at, "
            + "last_attempt = EXCLUDED.last_attempt");
      }
      return;
    }
    change(list -> {
      for (Expiry e : records) {
        list.removeIf(x -> x.same(e.definitionId(), e.groupDn(), e.member()));
        list.add(e);
      }
    });
  }

  /** Forgets a membership's end date (the membership ended, or has no end any more). */
  public void clear(String definitionId, String groupDn, String member) {
    clear(definitionId, groupDn, List.of(member));
  }

  /** Forgets the end dates of these members of a group. */
  public void clear(String definitionId, String groupDn, Collection<String> members) {
    if (members.isEmpty()) return;
    if (jdbc != null) {
      for (String member : members) jdbc.update("DELETE FROM dsp_group_membership_expiry WHERE id = ?", id(definitionId, groupDn, member));
      return;
    }
    if (file.list().stream().noneMatch(x -> members.stream().anyMatch(m -> x.same(definitionId, groupDn, m)))) return;
    change(list -> list.removeIf(x -> members.stream().anyMatch(m -> x.same(definitionId, groupDn, m))));
  }

  /**
   * Removes a record as it was read (same end date): false when it changed or went meanwhile, so
   * one caller only acts on it.
   */
  public boolean remove(Expiry e) {
    if (jdbc != null) {
      return jdbc.update("DELETE FROM dsp_group_membership_expiry WHERE id = ? AND expires_on = ?", id(e.definitionId(), e.groupDn(), e.member()),
          Date.valueOf(e.untilDate())) > 0;
    }
    boolean[] removed = {false};
    change(list -> removed[0] = list.removeIf(x -> x.same(e.definitionId(), e.groupDn(), e.member()) && x.until().equals(e.until())));
    return removed[0];
  }

  /** Notes that removing the member failed at {@code at} (it is tried again later). */
  public void attempted(Expiry e, Instant at) {
    String when = at.truncatedTo(ChronoUnit.MILLIS).toString();
    if (jdbc != null) {
      jdbc.update("UPDATE dsp_group_membership_expiry SET last_attempt = ? WHERE id = ?", ts(when), id(e.definitionId(), e.groupDn(), e.member()));
      return;
    }
    change(list -> list.replaceAll(x -> x.same(e.definitionId(), e.groupDn(), e.member()) ? x.attempted(when) : x));
  }

  public Expiry find(String definitionId, String groupDn, String member) {
    if (jdbc != null) {
      List<Expiry> rows = jdbc.query("SELECT " + COLUMNS + " FROM dsp_group_membership_expiry WHERE id = ?", MembershipExpiries::row,
          id(definitionId, groupDn, member));
      return rows.isEmpty() ? null : rows.get(0);
    }
    return file.list().stream().filter(x -> x.same(definitionId, groupDn, member)).findFirst().orElse(null);
  }

  /** The end dates recorded for a group's members. */
  public List<Expiry> ofGroup(String definitionId, String groupDn) {
    if (jdbc != null) {
      return jdbc.query("SELECT " + COLUMNS + " FROM dsp_group_membership_expiry WHERE group_key = ?", MembershipExpiries::row,
          groupKey(definitionId, groupDn));
    }
    String group = normal(groupDn);
    return file.list().stream().filter(x -> x.definitionId().equals(definitionId) && normal(x.groupDn()).equals(group)).toList();
  }

  /** The end dates recorded for one member, in a definition's groups. */
  public List<Expiry> ofMember(String definitionId, String member) {
    if (jdbc != null) {
      return jdbc.query("SELECT " + COLUMNS + " FROM dsp_group_membership_expiry WHERE member_key = ?", MembershipExpiries::row,
          memberKey(definitionId, member));
    }
    String value = normal(member);
    return file.list().stream().filter(x -> x.definitionId().equals(definitionId) && normal(x.member()).equals(value)).toList();
  }

  /** Every record, a group's together. */
  public List<Expiry> all() {
    if (jdbc != null) return jdbc.query("SELECT " + COLUMNS + " FROM dsp_group_membership_expiry ORDER BY group_key, id", MembershipExpiries::row);
    return file.list();
  }

  /** Removes every record (tests reset the portal's state with it). */
  public void clearAll() {
    if (jdbc != null) {
      jdbc.update("DELETE FROM dsp_group_membership_expiry");
      return;
    }
    change(List::clear);
  }
}
