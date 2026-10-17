package com.winllc.dsp.audit;

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
 */
public class AuditState {
  private static final Logger log = LoggerFactory.getLogger(AuditState.class);
  public static final String FILE = "audit-state.json";

  public record Cursor(long seq, String time) {}

  private final Path file;
  private final JsonMapper mapper;
  private final Map<String, Cursor> cursors = new LinkedHashMap<>();
  private String licenseMode;

  public AuditState(Path dir, JsonMapper mapper) {
    this.file = dir.resolve(FILE);
    this.mapper = mapper;
    load();
  }

  private void load() {
    if (!Files.exists(file)) return;
    try {
      JsonNode root = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
      JsonNode c = root.get("cursors");
      if (c != null && c.isObject()) {
        for (var e : c.properties()) {
          JsonNode t = e.getValue().get("time");
          cursors.put(e.getKey(), new Cursor(e.getValue().get("seq").asLong(), t == null || t.isNull() ? null : t.asString()));
        }
      }
      JsonNode mode = root.get("licenseMode");
      licenseMode = mode == null || mode.isNull() ? null : mode.asString();
    } catch (IOException | RuntimeException e) {
      log.error("Could not read {}: {}; forwarders start from the beginning", file, e.getMessage());
    }
  }

  public synchronized Cursor cursor(String output) {
    return cursors.getOrDefault(output, new Cursor(0, null));
  }

  public synchronized void setCursor(String output, long seq, String time) throws IOException {
    cursors.put(output, new Cursor(seq, time));
    save();
  }

  public synchronized String licenseMode() {
    return licenseMode;
  }

  public synchronized void setLicenseMode(String mode) throws IOException {
    licenseMode = mode;
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
}
