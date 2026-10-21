package com.winllc.dsp.web;

import com.winllc.dsp.integration.IntegrationProperties;
import com.winllc.dsp.jobs.Job;
import com.winllc.dsp.jobs.JobService;
import com.winllc.dsp.license.LicensedFeature;
import com.winllc.dsp.license.RequiresFeature;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.service.EntryService;
import com.winllc.dsp.service.Memberships;
import com.winllc.dsp.transfer.EntryTransfer;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Export (CSV, LDIF) of a directory's search results and of a group's members, import of CSV or
 * LDIF with a dry run (as a background job when it takes more than a few seconds), and bulk
 * membership changes, for signed-in users with the permissions these need in the portal.
 */
@RestController
@RequiresFeature(LicensedFeature.DIRECTORY_EDITOR)
@RequestMapping("/api/directories/{id}")
public class TransferController {
  private static final Duration WAIT = Duration.ofSeconds(3);

  private final EntryService entries;
  private final EntryTransfer transfer;
  private final Memberships memberships;
  private final JobService jobs;
  private final IntegrationProperties props;

  public TransferController(EntryService entries, EntryTransfer transfer, Memberships memberships, JobService jobs, IntegrationProperties props) {
    this.entries = entries;
    this.transfer = transfer;
    this.memberships = memberships;
    this.jobs = jobs;
    this.props = props;
  }

  private static ResponseEntity<String> file(String content, EntryTransfer.Format format, String name, int count, boolean truncated) {
    String ext = format == EntryTransfer.Format.CSV ? "csv" : "ldif";
    MediaType type = format == EntryTransfer.Format.CSV ? new MediaType("text", "csv", StandardCharsets.UTF_8)
        : new MediaType("text", "plain", StandardCharsets.UTF_8);
    String filename = name.replaceAll("[^A-Za-z0-9._-]+", "-") + "-" + LocalDate.now(ZoneOffset.UTC) + "." + ext;
    return ResponseEntity.ok()
        .contentType(type)
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
        .header("X-DSP-Export-Count", Integer.toString(count))
        .header("X-DSP-Export-Truncated", Boolean.toString(truncated))
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(content);
  }

  /** The directory's entries matching {@code q} (all of them without it), up to EXPORT_MAX_ENTRIES. */
  @GetMapping("/export")
  public ResponseEntity<String> export(@AuthenticationPrincipal SessionUser user, @PathVariable String id,
      @RequestParam(required = false) String q, @RequestParam(required = false) String format) {
    if (q != null && q.length() > 256) throw ApiException.badRequest("Search text is too long");
    EntryTransfer.Format f = EntryTransfer.Format.parse(format);
    DirectoryDefinition def = entries.definition(id);
    EntryTransfer.Export e = transfer.export(user, def, q, f);
    return file(e.content(), f, def.slug(), e.count(), e.truncated());
  }

  @GetMapping("/members")
  public Memberships.Members members(@AuthenticationPrincipal SessionUser user, @PathVariable String id, @RequestParam(required = false) String dn,
      @RequestParam(required = false) String attribute) {
    return memberships.read(user, entries.definition(id), DirectoryController.dnParam(dn), attribute);
  }

  @GetMapping("/members/export")
  public ResponseEntity<String> exportMembers(@AuthenticationPrincipal SessionUser user, @PathVariable String id,
      @RequestParam(required = false) String dn, @RequestParam(required = false) String attribute, @RequestParam(required = false) String format) {
    EntryTransfer.Format f = EntryTransfer.Format.parse(format);
    DirectoryDefinition def = entries.definition(id);
    String group = DirectoryController.dnParam(dn);
    EntryTransfer.Export e = transfer.exportMembers(user, def, group, attribute, f);
    return file(e.content(), f, com.winllc.dsp.ldap.Dns.rdnValue(group) + "-members", e.count(), false);
  }

  public record MembershipChange(String attribute, List<String> add, List<String> remove) {}

  /** Adds and removes many members in one change of the group. */
  @PostMapping("/members")
  public Memberships.Change changeMembers(@AuthenticationPrincipal SessionUser user, @PathVariable String id,
      @RequestParam(required = false) String dn, @RequestBody MembershipChange body) {
    return memberships.change(user, entries.definition(id), DirectoryController.dnParam(dn), body.attribute(), body.add(), body.remove());
  }

  /**
   * Imports the request body (CSV or LDIF, UTF-8, at most IMPORT_MAX_BYTES and IMPORT_MAX_ROWS).
   * {@code dryRun=true} checks every row without writing. Answers the result, or (202) the job
   * still running it.
   */
  @PostMapping(path = "/import", consumes = {"text/csv", "text/plain", "application/ldif", "application/octet-stream"})
  public ResponseEntity<Object> importEntries(@AuthenticationPrincipal SessionUser user, @PathVariable String id,
      @RequestParam(required = false) String format, @RequestParam(required = false) String mode,
      @RequestParam(required = false, defaultValue = "false") boolean dryRun, HttpServletRequest request) throws IOException {
    EntryTransfer.Format f = EntryTransfer.Format.parse(format);
    EntryTransfer.Mode m = EntryTransfer.Mode.parse(mode);
    DirectoryDefinition def = entries.definition(id);
    String text = readLimited(request.getInputStream(), props.getTransfer().getImportMaxBytes());
    List<EntryTransfer.Row> parsed = transfer.parse(user, def, text, f);
    String ip = request.getRemoteAddr();
    String agent = request.getHeader("User-Agent");
    JobService.Spec spec = new JobService.Spec("import", (dryRun ? "Check import into " : "Import into ") + def.name(),
        new Job.Target("directory", def.id()), "/d/" + def.slug(), null, JobService.Pool.ANALYSIS, null);
    return JobsController.respond(jobs.runOrWait(user, spec, () -> transfer.run(user, def, parsed, f, m, dryRun, ip, agent), WAIT, true));
  }

  static String readLimited(InputStream in, int max) throws IOException {
    byte[] bytes = in.readNBytes(max + 1);
    if (bytes.length > max) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "The file is larger than " + max + " bytes (IMPORT_MAX_BYTES)");
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
