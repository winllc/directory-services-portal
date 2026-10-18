package com.winllc.dsp.audit;

import com.winllc.dsp.cluster.SharedDocuments;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code audit-state.json} next to the store: each forwarder's cursor (the last seq it delivered,
 * and when) and the license mode last recorded. Written atomically (temporary file, then rename).
 * With the shared database it is the {@value #DOCUMENT} document instead (imported from the file
 * once), written by the replica that runs the forwarders and read again with {@link #refresh}.
 */
public class AuditState {
  private static final Logger log = LoggerFactory.getLogger(AuditState.class);
  public static final String FILE = "audit-state.json";

  public record Cursor(long seq, String time) {}

  static final String DOCUMENT = "audit-state";

  private final Path file;
  private final JsonMapper mapper;
  private final SharedDocuments shared;
  private final Map<String, Cursor> cursors = new LinkedHashMap<>();
  private String licenseMode;

  public AuditState(Path dir, JsonMapper mapper) {
    this(dir, mapper, null);
  }

  public AuditState(Path dir, JsonMapper mapper, SharedDocuments shared) {
    this.file = dir.resolve(FILE);
    this.mapper = mapper;
    this.shared = shared;
    load();
  }

  private void load() {
    if (shared != null) {
      try {
        SharedDocuments.Doc doc = shared.get(DOCUMENT);
        if (!doc.exists() && Files.exists(file)) {
          doc = shared.createIfAbsent(DOCUMENT, Files.readString(file, StandardCharsets.UTF_8));
        }
        if (doc.exists()) read(doc.content());
      } catch (IOException | RuntimeException e) {
        log.error("Could not read the shared audit state: {}; forwarders start from the beginning", e.getMessage());
      }
      return;
    }
    if (!Files.exists(file)) return;
    try {
      read(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException | RuntimeException e) {
      log.error("Could not read {}: {}; forwarders start from the beginning", file, e.getMessage());
    }
  }

  /** With the shared database: reads what other replicas recorded (no-op alone). */
  public synchronized void refresh() {
    if (shared == null) return;
    try {
      SharedDocuments.Doc doc = shared.get(DOCUMENT);
      if (doc.exists()) read(doc.content());
    } catch (RuntimeException e) {
      log.warn("Could not read the shared audit state: {}", e.getMessage());
    }
  }

  private void read(String json) {
    JsonNode root = mapper.readTree(json);
    JsonNode c = root.get("cursors");
    if (c != null && c.isObject()) {
      for (var e : c.properties()) {
        JsonNode t = e.getValue().get("time");
        cursors.put(e.getKey(), new Cursor(e.getValue().get("seq").asLong(), t == null || t.isNull() ? null : t.asString()));
      }
    }
    JsonNode mode = root.get("licenseMode");
    licenseMode = mode == null || mode.isNull() ? null : mode.asString();
  }

  public synchronized Cursor cursor(String output) {
    return cursors.getOrDefault(output, new Cursor(0, null));
  }

  public synchronized void setCursor(String output, long seq, String time) throws IOException {
    cursors.put(output, new Cursor(seq, time));
    if (shared != null) {
      // Only forward: a replica that lost the leadership without noticing can't move it back.
      saveShared(root -> {
        JsonNode existing = root.get("cursors");
        ObjectNode c = existing instanceof ObjectNode o ? o : root.putObject("cursors");
        JsonNode previous = c.get(output);
        if (previous != null && previous.get("seq") != null && previous.get("seq").asLong() >= seq) return;
        c.putObject(output).put("seq", seq).put("time", time);
      });
      return;
    }
    save();
  }

  public synchronized String licenseMode() {
    return licenseMode;
  }

  public synchronized void setLicenseMode(String mode) throws IOException {
    licenseMode = mode;
    if (shared != null) {
      saveShared(root -> root.put("licenseMode", mode));
      return;
    }
    save();
  }

  private void save() throws IOException {
    ObjectNode root = mapper.createObjectNode();
    ObjectNode c = root.putObject("cursors");
    cursors.forEach((k, v) -> c.putObject(k).put("seq", v.seq()).put("time", v.time()));
    root.put("licenseMode", licenseMode);
    Files.createDirectories(file.getParent());
    Path tmp = file.resolveSibling(FILE + ".tmp");
    Files.writeString(tmp, mapper.writeValueAsString(root), StandardCharsets.UTF_8);
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  /** Changes only the given field of the shared document (other replicas may have written others). */
  private void saveShared(java.util.function.Consumer<ObjectNode> change) throws IOException {
    try {
      shared.update(DOCUMENT, current -> {
        ObjectNode root = current == null ? mapper.createObjectNode() : (ObjectNode) mapper.readTree(current);
        change.accept(root);
        return mapper.writeValueAsString(root);
      });
    } catch (RuntimeException e) {
      throw new IOException("Could not store the audit state: " + e.getMessage(), e);
    }
  }
}
