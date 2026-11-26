package com.winllc.dsp.expiry;

import com.unboundid.ldap.sdk.Filter;
import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.cluster.Cluster;
import com.winllc.dsp.ldap.Directories;
import com.winllc.dsp.ldap.DirectoryException;
import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.model.DirectoryEntry;
import com.winllc.dsp.model.EntryExpiry;
import com.winllc.dsp.model.FormField;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.service.Dates;
import com.winllc.dsp.service.EntryService;
import com.winllc.dsp.service.Mailer;
import com.winllc.dsp.store.ConfigStore;
import com.winllc.dsp.util.LogSafe;
import com.winllc.dsp.web.ApiException;
import com.winllc.dsp.webhooks.WebhookService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Entries that end on a date ({@link EntryExpiry}): contractors, guests. Each day the sponsor
 * of every entry ending within the definition's warning days is mailed, the
 * {@code entry.expiring} webhook event is sent and the notice is audited. The sponsor may move
 * the end date out, at most the longest extension from today, without write access to the
 * definition: the change is an {@code entry.update} with {@code details.via = "sponsor-renewal"},
 * as a group owner's member change is {@code group-owner}.
 */
@Service
public class EntryExpiryService {
  private static final Logger log = LoggerFactory.getLogger(EntryExpiryService.class);
  public static final String VIA_SPONSOR = "sponsor-renewal";
  /** Most entries read per definition in one run. */
  static final int SCAN_LIMIT = 5_000;

  /** An entry the user sponsors, with its end date and how far it may be moved. */
  public record Sponsored(String definitionId, String definitionName, String dn, String name, String endDate, long daysLeft,
      String latestDate) {}

  /** What one run did. */
  public record Run(int entries, int notified, int mailed) {}

  private final ConfigStore store;
  private final EntryService entries;
  private final Directories directories;
  private final Mailer mailer;
  private final WebhookService webhooks;
  private final AuditLog audit;
  private final Cluster cluster;
  private Clock clock = Clock.systemUTC();

  public EntryExpiryService(ConfigStore store, EntryService entries, Directories directories, Mailer mailer, WebhookService webhooks,
      AuditLog audit, Cluster cluster) {
    this.store = store;
    this.entries = entries;
    this.directories = directories;
    this.mailer = mailer;
    this.webhooks = webhooks;
    this.audit = audit;
    this.cluster = cluster;
  }

  /** For tests: a fixed today. */
  public void useClock(Clock c) {
    this.clock = c;
  }

  LocalDate today() {
    return LocalDate.now(clock.withZone(ZoneOffset.UTC));
  }

  private List<DirectoryDefinition> definitions() {
    return store.snapshot().definitions.stream().filter(DirectoryDefinition::entryExpiry).filter(d -> directories.available(d.directory())).toList();
  }

  /** Daily at 06:07 UTC, on one replica. */
  @Scheduled(cron = "0 7 6 * * *", zone = "UTC")
  public void daily() {
    cluster.runLocked("entry-expiry", Duration.ofMinutes(30), Duration.ofMinutes(1), () -> {
      Run r = run();
      if (r.notified() > 0) log.info("Entry expiry: {} entries ending soon, {} sponsors mailed", r.notified(), r.mailed());
    });
  }

  /** One run, now: every entry ending within its warning days. */
  public Run run() {
    int seen = 0;
    int notified = 0;
    int mailed = 0;
    LocalDate today = today();
    for (DirectoryDefinition def : definitions()) {
      EntryExpiry x = def.expiry();
      List<DirectoryEntry> found;
      try {
        found = entries.searchInNamespace(def, null, Filter.createPresenceFilter(x.dateAttribute()), SCAN_LIMIT,
            List.of(def.titleAttribute(), x.dateAttribute(), x.sponsorAttribute())).entries();
      } catch (RuntimeException e) {
        log.warn("Entry expiry: could not read {}: {}", LogSafe.of(def.name()), LogSafe.of(e.getMessage()));
        continue;
      }
      for (DirectoryEntry e : found) {
        seen++;
        LocalDate end = Dates.parse(e.first(x.dateAttribute()));
        if (end == null) continue;
        long left = ChronoUnit.DAYS.between(today, end);
        if (left < 0 || left > x.warnDaysOrDefault()) continue;
        notified++;
        if (notify(def, e, end, left)) mailed++;
      }
    }
    return new Run(seen, notified, mailed);
  }

  private static String title(DirectoryDefinition def, DirectoryEntry e) {
    String t = e.first(def.titleAttribute());
    return t != null ? t : Dns.rdnValue(e.dn());
  }

  /** Mails the entry's sponsors, sends the webhook event and audits the notice. True when mail went out. */
  private boolean notify(DirectoryDefinition def, DirectoryEntry e, LocalDate end, long left) {
    EntryExpiry x = def.expiry();
    List<String> sponsors = e.values(x.sponsorAttribute());
    List<String> to = new ArrayList<>();
    for (String s : sponsors) {
      if (!Dns.isValid(s)) continue;
      try {
        DirectoryEntry p = directories.forDefinition(def).ldap().get(s, List.of("mail"));
        if (p != null && p.first("mail") != null) to.add(p.first("mail"));
      } catch (DirectoryException ignored) {
        // A sponsor the portal can't read is named in the event and the audit, not mailed.
      }
    }
    String name = title(def, e);
    String when = left == 0 ? "today" : "in " + left + (left == 1 ? " day" : " days");
    boolean sent = !to.isEmpty() && mailer.send(to, name + " ends " + when,
        "The entry " + name + " in " + def.name() + ", which you sponsor, ends " + when + " (" + end + ").\n\n"
            + "To keep it, extend its end date (at most " + x.maxExtensionDaysOrDefault() + " days from today) in the Directory "
            + "Services Portal: Sponsored entries.\n");
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("definitionId", def.id());
    if (def.directory() != null) data.put("directory", def.directory());
    data.put("dn", e.dn());
    data.put("name", name);
    data.put("endDate", end.toString());
    data.put("daysLeft", left);
    data.put("sponsors", sponsors);
    webhooks.raise("entry.expiring", def.id(), Map.of("username", "system", "authMethod", "system"), data);
    audit.event("entry.expiring").system().definition(def.id()).directory(entries.directoryId(def)).target("entry", e.dn(), name)
        .detail("endDate", end.toString()).detail("daysLeft", left).detail("sponsors", sponsors.size()).detail("mailed", sent).success();
    return sent;
  }

  // ---------------------------------------------------------------------------
  // Sponsors
  // ---------------------------------------------------------------------------

  private DirectoryDefinition definition(SessionUser user, String id) {
    return definitions().stream().filter(d -> d.id().equals(id) && directories.same(d.directory(), user.directory())).findFirst()
        .orElseThrow(() -> ApiException.notFound("No such directory"));
  }

  /** The entries with an end date the user sponsors, in their own directory. */
  public List<Sponsored> sponsored(SessionUser user) {
    List<Sponsored> out = new ArrayList<>();
    LocalDate today = today();
    for (DirectoryDefinition def : definitions()) {
      if (!directories.same(def.directory(), user.directory())) continue;
      EntryExpiry x = def.expiry();
      // Only entries that end: a sponsor attribute alone (a manager) is not a sponsorship.
      Filter mine = Filter.createANDFilter(Filter.createEqualityFilter(x.sponsorAttribute(), user.dn()),
          Filter.createPresenceFilter(x.dateAttribute()));
      List<DirectoryEntry> found = entries.searchInNamespace(def, null, mine, SCAN_LIMIT,
          List.of(def.titleAttribute(), x.dateAttribute())).entries();
      for (DirectoryEntry e : found) {
        LocalDate end = Dates.parse(e.first(x.dateAttribute()));
        out.add(new Sponsored(def.id(), def.name(), e.dn(), title(def, e), end == null ? null : end.toString(),
            end == null ? 0 : ChronoUnit.DAYS.between(today, end), today.plusDays(x.maxExtensionDaysOrDefault()).toString()));
      }
    }
    out.sort(java.util.Comparator.comparing(Sponsored::daysLeft));
    return out;
  }

  /** Moves an entry's end date to {@code date}, as its sponsor. */
  public Sponsored renew(SessionUser user, String definitionId, String dn, String date) {
    DirectoryDefinition def = definition(user, definitionId);
    EntryExpiry x = def.expiry();
    DirectoryEntry e = entries.readInNamespace(def, dn, List.of(def.titleAttribute(), x.dateAttribute(), x.sponsorAttribute()));
    if (e.values(x.sponsorAttribute()).stream().noneMatch(s -> Dns.equal(s, user.dn()))) {
      throw ApiException.forbidden("Only the entry's sponsor can extend it");
    }
    LocalDate want = Dates.parse(date);
    if (want == null) throw ApiException.badRequest("Not a date", Map.of("date", "yyyy-mm-dd"));
    LocalDate today = today();
    LocalDate latest = today.plusDays(x.maxExtensionDaysOrDefault());
    if (want.isAfter(latest)) {
      throw ApiException.badRequest("At most " + x.maxExtensionDaysOrDefault() + " days from today",
          Map.of("date", "At most " + latest));
    }
    if (!want.isAfter(today)) throw ApiException.badRequest("The new end date must be after today", Map.of("date", "After " + today));
    String old = e.first(x.dateAttribute());
    LocalDate current = Dates.parse(old);
    if (current != null && !want.isAfter(current)) {
      throw new ApiException(HttpStatus.CONFLICT, "The entry already ends on " + current + ": a sponsor extends, never shortens");
    }
    FormField field = entries.form(def).fields().stream().filter(f -> f.attribute().equalsIgnoreCase(x.dateAttribute())).findFirst()
        .orElseThrow(() -> ApiException.badRequest("The form has no end date field"));
    String value = Dates.format(want, old);
    entries.changeValues(user, def, e.dn(), field, List.of(value), old == null ? List.of() : List.of(old), false, VIA_SPONSOR);
    return new Sponsored(def.id(), def.name(), e.dn(), title(def, e), want.toString(), ChronoUnit.DAYS.between(today, want), latest.toString());
  }
}
