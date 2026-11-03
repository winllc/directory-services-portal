package com.winllc.dsp.store;

import com.winllc.dsp.cluster.Cluster;
import com.winllc.dsp.cluster.DatabaseProperties;
import com.winllc.dsp.cluster.SharedDocuments;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.config.DirectoryProperties;
import com.winllc.dsp.model.CustomSchema;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.model.PermissionGrant;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Persists portal configuration as a single JSON document. Updates are serialized, applied to a
 * deep copy (so a failing mutation changes nothing) and only then made current.
 *
 * <p>Alone, the document is {@code <dataDir>/portal-config.json}, written atomically (temp file +
 * rename). With the shared database it is the {@value #DOCUMENT} row of {@code dsp_document}: an
 * update locks it, applies the change to the latest version (whichever replica wrote it) and writes
 * the next version; reads check at most every {@code DSP_CONFIG_REFRESH} whether another replica
 * has written a newer one. On the first start with the database, an existing
 * {@code portal-config.json} is imported (the file is left as it was).
 */
@Component
public class ConfigStore {
  private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);
  static final String DOCUMENT = "portal-config";
  static final String IMPORT_MARKER = "import/portal-config";

  private final JsonMapper mapper;
  private final Path file;
  private final SharedDocuments shared;
  private final long refreshMillis;
  private volatile StoreData data;
  private volatile long version;
  private volatile long checkedAt;

  public ConfigStore(JsonMapper mapper, PortalProperties props, Cluster cluster, DatabaseProperties database) {
    this.mapper = mapper;
    this.file = Path.of(props.getDataDir()).toAbsolutePath().resolve("portal-config.json");
    this.shared = cluster.shared() ? cluster.documents() : null;
    Duration refresh = database.getConfigRefresh();
    this.refreshMillis = refresh == null ? 0 : Math.max(0, refresh.toMillis());
    if (shared == null) {
      this.data = loadFile();
      if (Files.exists(file)) persistMigration(loadedVersion);
    } else {
      StoreData local = loadFile();
      boolean hasLocal = hasContent(local);
      SharedDocuments.Doc doc = shared.get(DOCUMENT);
      if (!doc.exists()) {
        // Created from this instance's file when it has one (the first start of an upgraded
        // single instance), else empty; the marker records that the file was imported.
        doc = shared.createIfAbsent(DOCUMENT, mapper.writeValueAsString(local));
        if (hasLocal && doc.version() == 1 && doc.content().equals(mapper.writeValueAsString(local))) {
          shared.createIfAbsent(IMPORT_MARKER, contentHash(local));
          log.info("Imported the portal configuration from {} into the shared database", file);
        }
      }
      SharedDocuments.Doc marker = shared.get(IMPORT_MARKER);
      if (hasLocal && marker.exists() && !marker.content().equals(contentHash(local)) && !marker.content().equals(legacyHash(local))) {
        // The database copy wins; say so, so an operator can tell this file is not in use.
        log.warn("{} differs from the configuration imported into the shared database; this file is not used (the shared"
            + " configuration is). Move it away once you have checked nothing in it is missing.", file);
      }
      if (hasLocal && !marker.exists()) {
        throw new IllegalStateException("Refusing to start: the shared database's configuration was created without importing "
            + file + ", which holds this instance's forms, definitions and grants. Upgrade a single instance to the database"
            + " first, with its data volume, so it imports them; or, if the database's configuration is the one to keep, move "
            + file + " away and start again.");
      }
      this.data = parse(doc.content());
      this.version = doc.version();
      this.checkedAt = System.currentTimeMillis();
      persistMigration(loadedVersion);
    }
  }

  /** The version of the configuration as last read, before {@link #normalize} upgraded it. */
  private int loadedVersion = StoreData.CURRENT_VERSION;

  private StoreData loadFile() {
    if (!Files.exists(file)) return new StoreData();
    try {
      StoreData raw = mapper.readValue(file.toFile(), StoreData.class);
      loadedVersion = raw.version;
      StoreData loaded = normalize(raw);
      log.info("Loaded portal configuration from {}", file);
      return loaded;
    } catch (RuntimeException e) {
      throw new IllegalStateException("Could not read " + file + ": " + e.getMessage(), e);
    }
  }

  /** SHA-256 of a configuration as JSON: the import marker, to tell another instance's file from it. */
  private String contentHash(StoreData d) {
    return com.winllc.dsp.audit.AuditJson.sha256Hex(mapper.writeValueAsString(d));
  }

  /** {@link #contentHash} of the configuration as version 1 wrote it (no directories), as imported by an earlier version. */
  private String legacyHash(StoreData d) {
    StoreData v1 = mapper.convertValue(d, StoreData.class);
    v1.version = 1;
    v1.schemaCaches = null;
    v1.definitions.replaceAll(x -> x.withDirectory(null));
    v1.grants.replaceAll(x -> x.withDirectory(null));
    return contentHash(v1);
  }

  /** Whether a configuration holds anything an administrator made (worth importing). */
  static boolean hasContent(StoreData d) {
    return !d.forms.isEmpty() || !d.definitions.isEmpty() || !d.grants.isEmpty() || !d.dashboards.isEmpty() || !d.analyses.isEmpty();
  }

  private StoreData parse(String json) {
    StoreData raw = mapper.readValue(json, StoreData.class);
    loadedVersion = raw.version;
    return normalize(raw);
  }

  private static StoreData normalize(StoreData loaded) {
    if (loaded.customSchema == null) loaded.customSchema = CustomSchema.empty();
    // Files written before dashboards and analyses existed.
    if (loaded.dashboards == null) loaded.dashboards = new java.util.ArrayList<>();
    if (loaded.analyses == null) loaded.analyses = new java.util.ArrayList<>();
    if (loaded.schemaCaches == null) loaded.schemaCaches = new java.util.LinkedHashMap<>();
    migrateDirectories(loaded);
    return loaded;
  }

  /**
   * Version 2 (several LDAP directories): every definition belongs to a directory, and every grant
   * names the directory of its subject. What was stored before has neither, and belongs to the one
   * directory there was: {@value DirectoryProperties#DEFAULT_ID}, which also stands for the primary
   * directory when no directory has that id. Returns whether anything changed.
   */
  static boolean migrateDirectories(StoreData d) {
    boolean changed = d.version < StoreData.CURRENT_VERSION;
    if (d.definitions != null) {
      for (int i = 0; i < d.definitions.size(); i++) {
        DirectoryDefinition def = d.definitions.get(i);
        if (def.directory() == null || def.directory().isBlank()) {
          d.definitions.set(i, def.withDirectory(DirectoryProperties.DEFAULT_ID));
          changed = true;
        }
      }
    }
    if (d.grants != null) {
      for (int i = 0; i < d.grants.size(); i++) {
        PermissionGrant g = d.grants.get(i);
        if (g.directory() == null || g.directory().isBlank()) {
          String owner = d.definitions == null ? null : d.definitions.stream().filter(x -> x.id().equals(g.definitionId()))
              .map(DirectoryDefinition::directory).findFirst().orElse(null);
          d.grants.set(i, g.withDirectory(owner != null ? owner : DirectoryProperties.DEFAULT_ID));
          changed = true;
        }
      }
    }
    d.version = Math.max(d.version, StoreData.CURRENT_VERSION);
    return changed;
  }

  /** Writes a configuration of an earlier version back in the current one (once, at startup). */
  private void persistMigration(int loadedVersion) {
    if (loadedVersion >= StoreData.CURRENT_VERSION) return;
    try {
      update(d -> null);
    } catch (RuntimeException e) {
      // In use as upgraded either way; written with the next change.
      log.warn("Could not write the upgraded portal configuration back ({}); it is used as upgraded and written with the next change",
          e.getMessage());
      return;
    }
    log.info("Portal configuration upgraded to version {}: definitions and grants without a directory now belong to \"{}\"",
        StoreData.CURRENT_VERSION, DirectoryProperties.DEFAULT_ID);
  }

  /** Current configuration. Treat as read-only; use {@link #update} to change it. */
  public StoreData snapshot() {
    if (shared != null && System.currentTimeMillis() - checkedAt >= refreshMillis) refresh();
    return data;
  }

  /** Reads the shared document again when another replica wrote a newer version. */
  private synchronized void refresh() {
    if (System.currentTimeMillis() - checkedAt < refreshMillis) return;
    try {
      long current = shared.version(DOCUMENT);
      if (current != version) {
        SharedDocuments.Doc doc = shared.get(DOCUMENT);
        if (doc.exists()) {
          data = parse(doc.content());
          version = doc.version();
          log.debug("Configuration version {} loaded (changed on another replica)", version);
        }
      }
    } catch (RuntimeException e) {
      log.warn("Could not check the shared configuration for changes ({}); using version {}", e.getMessage(), version);
    }
    checkedAt = System.currentTimeMillis();
  }

  public synchronized <T> T update(Function<StoreData, T> mutator) {
    if (shared == null) {
      StoreData draft = mapper.convertValue(data, StoreData.class);
      T result = mutator.apply(draft);
      persist(draft);
      data = draft;
      return result;
    }
    Object[] out = new Object[2];
    SharedDocuments.Doc written = shared.update(DOCUMENT, current -> {
      StoreData draft = current == null ? new StoreData() : parse(current);
      out[0] = mutator.apply(draft);
      out[1] = draft;
      return mapper.writeValueAsString(draft);
    });
    data = (StoreData) out[1];
    version = written.version();
    checkedAt = System.currentTimeMillis();
    @SuppressWarnings("unchecked")
    T result = (T) out[0];
    return result;
  }

  private void persist(StoreData draft) {
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), draft);
      try {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Could not write " + file, e);
    }
  }
}
