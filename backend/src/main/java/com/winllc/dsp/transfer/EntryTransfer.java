package com.winllc.dsp.transfer;

import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldif.LDIFAddChangeRecord;
import com.unboundid.ldif.LDIFException;
import com.unboundid.ldif.LDIFReader;
import com.unboundid.ldif.LDIFRecord;
import com.unboundid.ldif.LDIFWriter;
import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.integration.IntegrationProperties;
import com.winllc.dsp.jobs.JobContext;
import com.winllc.dsp.ldap.DirectoryException;
import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.ldap.LdapDirectory;
import com.winllc.dsp.model.AccessLevel;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.model.DirectoryEntry;
import com.winllc.dsp.model.FormDefinition;
import com.winllc.dsp.model.FormField;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.service.EntryService;
import com.winllc.dsp.service.FormValidator;
import com.winllc.dsp.service.Memberships;
import com.winllc.dsp.web.ApiException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * CSV and LDIF export of a directory's entries (a search's results) and of a group's members, and
 * CSV and LDIF import with a dry run. Everything goes through {@link EntryService}: the reader's or
 * writer's permissions, the definition's namespace and form, and the per-entry audit trail apply
 * exactly as in the portal's pages. Only the attributes the directory's form shows are exported
 * (never passwords); only its fields are imported.
 */
@Service
public class EntryTransfer {
  public enum Format { CSV, LDIF;

    public static Format parse(String value) {
      if (value == null || value.isBlank() || value.equalsIgnoreCase("csv")) return CSV;
      if (value.equalsIgnoreCase("ldif")) return LDIF;
      throw ApiException.badRequest("format must be csv or ldif");
    }
  }

  /** create: new entries only; update: existing only; upsert: either (default). */
  public enum Mode { CREATE, UPDATE, UPSERT;

    public static Mode parse(String value) {
      if (value == null || value.isBlank()) return UPSERT;
      try {
        return valueOf(value.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw ApiException.badRequest("mode must be create, update or upsert");
      }
    }
  }

  public record Export(String content, int count, boolean truncated) {}

  /** One row's outcome: create, update, unchanged or error (with the reason and field messages). */
  public record RowResult(int row, String dn, String operation, List<String> attributes, String error, Map<String, String> details,
      List<String> ignored) {}

  public record ImportResult(String format, String mode, boolean dryRun, int total, int created, int updated, int unchanged, int failed,
      List<RowResult> rows) {}

  /** A parsed row: its number (CSV line or LDIF record), DN if given, values by attribute. */
  public record Row(int number, String dn, Map<String, Object> values, List<String> ignored, String error) {}

  private final EntryService entries;
  private final Memberships memberships;
  private final IntegrationProperties props;
  private final AuditLog audit;

  public EntryTransfer(EntryService entries, Memberships memberships, IntegrationProperties props, AuditLog audit) {
    this.entries = entries;
    this.memberships = memberships;
    this.props = props;
    this.audit = audit;
  }

  // ---------------------------------------------------------------------------
  // Export
  // ---------------------------------------------------------------------------

  /** The attributes exported for a definition: its form's fields (no secrets), DN first in CSV. */
  static List<FormField> exported(FormDefinition form) {
    return form.fields().stream().filter(f -> !EntryService.isHidden(f.attribute())).toList();
  }

  public Export export(SessionUser user, DirectoryDefinition def, String q, Format format) {
    FormDefinition form = entries.form(def);
    List<FormField> fields = exported(form);
    int max = Math.max(1, props.getTransfer().getExportMaxEntries());
    List<DirectoryEntry> found = new ArrayList<>();
    boolean truncated = entries.scan(user, def, q, max, found::add);
    found.sort((a, b) -> a.dn().compareToIgnoreCase(b.dn()));
    String content = format == Format.CSV ? csv(fields, found) : ldif(found);
    audit.event("data.exported").by(user).definition(def.id()).target("directory", def.id(), def.name())
        .detail("format", format.name().toLowerCase(Locale.ROOT)).detail("entries", found.size()).detail("truncated", truncated)
        .detail("query", q == null || q.isBlank() ? null : q).success();
    return new Export(content, found.size(), truncated);
  }

  private static String csv(List<FormField> fields, List<DirectoryEntry> found) {
    StringBuilder out = new StringBuilder(Csv.BOM);
    List<String> header = new ArrayList<>(List.of("dn"));
    fields.forEach(f -> header.add(f.attribute()));
    Csv.row(out, header);
    for (DirectoryEntry e : found) {
      List<String> cells = new ArrayList<>(List.of(e.dn()));
      for (FormField f : fields) {
        List<String> v = e.values(f.attribute());
        cells.add(f.multiValued() ? Csv.join(v) : v.isEmpty() ? "" : v.get(0));
      }
      Csv.row(out, cells);
    }
    return out.toString();
  }

  private static String ldif(List<DirectoryEntry> found) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (LDIFWriter writer = new LDIFWriter(bytes)) {
      writer.setWrapColumn(76);
      for (DirectoryEntry e : found) writer.writeEntry(toLdap(e));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return bytes.toString(StandardCharsets.UTF_8);
  }

  private static Entry toLdap(DirectoryEntry e) {
    List<Attribute> attrs = new ArrayList<>();
    e.attributes().forEach((name, values) -> {
      if (values.isEmpty() || EntryService.isHidden(name)) return;
      if (LdapDirectory.isBinary(name)) {
        attrs.add(new Attribute(name, values.stream().map(v -> Base64.getDecoder().decode(v)).toArray(byte[][]::new)));
      } else {
        attrs.add(new Attribute(name, values));
      }
    });
    return new Entry(e.dn(), attrs);
  }

  /** A group's members: CSV (one DN or value per row) or LDIF (the group with its member attribute). */
  public Export exportMembers(SessionUser user, DirectoryDefinition def, String dn, String attribute, Format format) {
    Memberships.Members m = memberships.read(user, def, dn, attribute);
    String content;
    if (format == Format.CSV) {
      StringBuilder out = new StringBuilder(Csv.BOM);
      Csv.row(out, List.of(m.attribute()));
      m.members().forEach(v -> Csv.row(out, List.of(v)));
      content = out.toString();
    } else {
      content = ldif(List.of(new DirectoryEntry(m.dn(), Map.of(m.attribute().toLowerCase(Locale.ROOT), m.members()))));
    }
    audit.event("data.exported").by(user).definition(def.id()).target("entry", m.dn(), Dns.rdnValue(m.dn()))
        .detail("format", format.name().toLowerCase(Locale.ROOT)).detail("members", m.members().size()).detail("attribute", m.attribute()).success();
    return new Export(content, m.members().size(), false);
  }

  // ---------------------------------------------------------------------------
  // Import
  // ---------------------------------------------------------------------------

  /** Checks a file before it is run: the user may write here, and its form is understood. */
  public List<Row> parse(SessionUser user, DirectoryDefinition def, String text, Format format) {
    entries.require(user, def, AccessLevel.WRITE);
    FormDefinition form = entries.form(def);
    int max = Math.max(1, props.getTransfer().getImportMaxRows());
    return format == Format.CSV ? parseCsv(form, text, max) : parseLdif(form, text, max);
  }

  private static Map<String, FormField> byAttribute(FormDefinition form) {
    Map<String, FormField> out = new LinkedHashMap<>();
    form.fields().forEach(f -> out.put(f.attribute().toLowerCase(Locale.ROOT), f));
    return out;
  }

  static List<Row> parseCsv(FormDefinition form, String text, int max) {
    boolean[] over = {false};
    List<Csv.Record> records;
    try {
      records = Csv.parse(text, max + 1, over);
    } catch (IllegalArgumentException e) {
      throw ApiException.badRequest(e.getMessage());
    }
    if (records.isEmpty()) throw ApiException.badRequest("The file is empty");
    if (over[0] || records.size() - 1 > max) throw ApiException.badRequest("The file has more than " + max + " rows (IMPORT_MAX_ROWS)");
    Map<String, FormField> fields = byAttribute(form);
    List<String> header = records.get(0).cells().stream().map(h -> h.trim()).toList();
    int dnColumn = -1;
    List<String> unknown = new ArrayList<>();
    List<FormField> columns = new ArrayList<>();
    for (int i = 0; i < header.size(); i++) {
      String h = header.get(i);
      if (h.equalsIgnoreCase("dn")) {
        if (dnColumn >= 0) throw ApiException.badRequest("The \"dn\" column appears twice");
        dnColumn = i;
        columns.add(null);
        continue;
      }
      FormField f = fields.get(h.toLowerCase(Locale.ROOT));
      if (f == null && !h.isEmpty()) unknown.add(h);
      columns.add(f);
    }
    if (!unknown.isEmpty()) {
      throw ApiException.badRequest("These columns are not fields of the directory's form: " + String.join(", ", unknown)
          + ". Use the attribute names of its fields (as an export writes them).");
    }
    List<Row> rows = new ArrayList<>();
    for (Csv.Record r : records.subList(1, records.size())) {
      if (r.cells().size() > header.size()) {
        rows.add(new Row(r.line(), null, Map.of(), List.of(), "The row has more cells than the header"));
        continue;
      }
      String dn = null;
      Map<String, Object> values = new LinkedHashMap<>();
      for (int i = 0; i < r.cells().size(); i++) {
        String cell = r.cells().get(i);
        if (i == dnColumn) {
          dn = cell.isBlank() ? null : cell.trim();
          continue;
        }
        FormField f = columns.get(i);
        if (f == null || cell.isEmpty()) continue;
        String v = Csv.unguard(cell);
        values.put(f.attribute(), f.multiValued() ? Csv.split(v) : v);
      }
      rows.add(new Row(r.line(), dn, values, List.of(), null));
    }
    return rows;
  }

  /**
   * Refuses LDIF with URL-valued attributes ({@code attr:< file:///...} or {@code http://...}):
   * the LDIF reader would fetch them, so an import could read the server's own files (the data
   * directory, /proc, keys) into an entry. UnboundID's reader has no option to turn this off, so
   * the text is checked first: continuation lines (starting with a space or tab) are unfolded, and
   * any non-comment line whose first colon is followed by {@code <} refuses the whole file.
   */
  static void refuseUrlValues(String text) {
    String[] lines = text.split("\r?\n|\r", -1);
    StringBuilder logical = new StringBuilder();
    int start = 0;
    for (int i = 0; i <= lines.length; i++) {
      boolean continuation = i < lines.length && !lines[i].isEmpty() && (lines[i].charAt(0) == ' ' || lines[i].charAt(0) == '\t');
      if (continuation) {
        logical.append(lines[i], 1, lines[i].length());
        continue;
      }
      if (logical.length() > 0 && logical.charAt(0) != '#') {
        int colon = logical.indexOf(":");
        if (colon >= 0 && colon + 1 < logical.length() && logical.charAt(colon + 1) == '<') {
          throw ApiException.badRequest("LDIF line " + (start + 1) + ": values given as a URL (\"" + logical.substring(0, colon)
              + ":<\") are not accepted; put the value in the file");
        }
      }
      if (i == lines.length) break;
      logical.setLength(0);
      logical.append(lines[i]);
      start = i;
    }
  }

  static List<Row> parseLdif(FormDefinition form, String text, int max) {
    refuseUrlValues(text);
    Map<String, FormField> fields = byAttribute(form);
    List<Row> rows = new ArrayList<>();
    try (LDIFReader reader = new LDIFReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)))) {
      int n = 0;
      while (true) {
        LDIFRecord rec;
        try {
          rec = reader.readLDIFRecord();
        } catch (LDIFException e) {
          if (!e.mayContinueReading()) throw ApiException.badRequest("LDIF line " + e.getLineNumber() + ": " + e.getExceptionMessage());
          rows.add(new Row(++n, null, Map.of(), List.of(), "Line " + e.getLineNumber() + ": " + e.getExceptionMessage()));
          continue;
        }
        if (rec == null) break;
        if (++n > max) throw ApiException.badRequest("The file has more than " + max + " records (IMPORT_MAX_ROWS)");
        Entry entry;
        if (rec instanceof Entry e) {
          entry = e;
        } else if (rec instanceof LDIFAddChangeRecord add) {
          entry = add.getEntryToAdd();
        } else {
          rows.add(new Row(n, rec.getDN(), Map.of(), List.of(), "Only entries (or changetype: add) can be imported; modify, delete and "
              + "moddn records are not supported"));
          continue;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> ignored = new ArrayList<>();
        for (Attribute a : entry.getAttributes()) {
          FormField f = fields.get(a.getBaseName().toLowerCase(Locale.ROOT));
          if (f == null) {
            if (!a.getBaseName().equalsIgnoreCase("objectClass")) ignored.add(a.getName());
            continue;
          }
          List<String> vals = LdapDirectory.isBinary(f.attribute())
              ? java.util.Arrays.stream(a.getValueByteArrays()).map(b -> Base64.getEncoder().encodeToString(b)).toList()
              : List.of(a.getValues());
          values.put(f.attribute(), f.multiValued() ? vals : vals.isEmpty() ? "" : vals.get(0));
        }
        rows.add(new Row(n, entry.getDN(), values, ignored, null));
      }
    } catch (IOException e) {
      throw ApiException.badRequest("The LDIF could not be read: " + e.getMessage());
    }
    if (rows.isEmpty()) throw ApiException.badRequest("The file has no entries");
    return rows;
  }

  /**
   * Runs (or with {@code dryRun}, checks) parsed rows one by one: an entry that exists in the
   * directory (by its DN, or by the DN its naming attribute gives in the first create container)
   * is updated with the values given; otherwise it is created. Empty cells leave an attribute as
   * it is. Each written entry is audited as usual, the import as a whole as {@code data.imported}.
   */
  public ImportResult run(SessionUser user, DirectoryDefinition def, List<Row> rows, Format format, Mode mode, boolean dryRun, String ip,
      String userAgent) {
    FormDefinition form = entries.form(def);
    List<RowResult> results = new ArrayList<>();
    int created = 0, updated = 0, unchanged = 0, failed = 0;
    String defaultParent = def.createContainers().isEmpty() ? def.baseDn() : def.createContainers().get(0);
    JobContext.current().phase(dryRun ? "Checking " + def.name() : "Importing into " + def.name(), (long) rows.size());
    for (Row row : rows) {
      JobContext.current().checkCancelled();
      JobContext.current().entryRead();
      RowResult r = runRow(user, def, form, row, mode, dryRun, defaultParent);
      results.add(r);
      switch (r.operation()) {
        case "create" -> created++;
        case "update" -> updated++;
        case "unchanged" -> unchanged++;
        default -> failed++;
      }
    }
    if (!dryRun) {
      audit.event("data.imported").by(user).definition(def.id()).target("directory", def.id(), def.name()).source(ip, userAgent)
          .detail("format", format.name().toLowerCase(Locale.ROOT)).detail("mode", mode.name().toLowerCase(Locale.ROOT))
          .detail("rows", rows.size()).detail("created", created).detail("updated", updated).detail("unchanged", unchanged)
          .detail("failed", failed).success();
    }
    return new ImportResult(format.name().toLowerCase(Locale.ROOT), mode.name().toLowerCase(Locale.ROOT), dryRun, rows.size(), created, updated,
        unchanged, failed, results);
  }

  private RowResult runRow(SessionUser user, DirectoryDefinition def, FormDefinition form, Row row, Mode mode, boolean dryRun, String defaultParent) {
    if (row.error() != null) return new RowResult(row.number(), row.dn(), "error", List.of(), row.error(), null, row.ignored());
    String dn = row.dn();
    Map<String, Object> values = new LinkedHashMap<>(row.values());
    try {
      String rdnAttr = form.rdnAttribute();
      if (dn != null) {
        if (dn.length() > 1024 || !Dns.isValid(dn)) throw ApiException.badRequest("Invalid DN");
        // The naming attribute may come from the DN alone.
        DN parsed = new DN(dn);
        if (!hasValue(values, rdnAttr) && parsed.getRDN().hasAttribute(rdnAttr)) {
          values.put(rdnAttr, parsed.getRDN().getAttributeValues()[0]);
        }
      } else {
        Object rdn = values.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(rdnAttr)).map(Map.Entry::getValue).findFirst().orElse(null);
        List<String> rdnValues = FormValidator.normalize(rdn);
        if (rdnValues.isEmpty()) throw ApiException.badRequest("Give a dn, or a value for the naming attribute " + rdnAttr);
        dn = Dns.build(rdnAttr, rdnValues.get(0), defaultParent);
      }
      boolean exists = entries.exists(def, dn);
      if (exists && mode == Mode.CREATE) throw ApiException.conflict("The entry already exists (mode create)");
      if (!exists && mode == Mode.UPDATE) throw ApiException.notFound("No such entry in this directory (mode update)");
      if (exists) {
        if (dryRun) {
          EntryService.Preview p = entries.previewUpdate(user, def, dn, values);
          return new RowResult(row.number(), p.dn(), p.operation(), p.attributes(), null, null, row.ignored());
        }
        EntryService.Preview p = entries.previewUpdate(user, def, dn, values);
        if ("unchanged".equals(p.operation())) return new RowResult(row.number(), dn, "unchanged", List.of(), null, null, row.ignored());
        DirectoryEntry after = entries.update(user, def, dn, values, false);
        return new RowResult(row.number(), after.dn(), "update", p.attributes(), null, null, row.ignored());
      }
      String parent = row.dn() != null ? new DN(dn).getParentString() : defaultParent;
      EntryService.Preview p = entries.previewCreate(user, def, parent, values);
      if (row.dn() != null && !Dns.equal(p.dn(), dn)) {
        throw ApiException.badRequest("The DN doesn't match the naming attribute " + rdnAttr + " (it would be " + p.dn() + ")");
      }
      if (dryRun) return new RowResult(row.number(), p.dn(), "create", p.attributes(), null, null, row.ignored());
      DirectoryEntry after = entries.create(user, def, parent, values);
      return new RowResult(row.number(), after.dn(), "create", p.attributes(), null, null, row.ignored());
    } catch (ApiException e) {
      return new RowResult(row.number(), dn, "error", List.of(), e.getMessage(), e.details(), row.ignored());
    } catch (DirectoryException e) {
      return new RowResult(row.number(), dn, "error", List.of(), e.getMessage(), null, row.ignored());
    } catch (LDAPException e) {
      return new RowResult(row.number(), dn, "error", List.of(), "Invalid DN", null, row.ignored());
    }
  }

  private static boolean hasValue(Map<String, Object> values, String attribute) {
    return values.entrySet().stream().anyMatch(e -> e.getKey().equalsIgnoreCase(attribute) && !FormValidator.normalize(e.getValue()).isEmpty());
  }
}
