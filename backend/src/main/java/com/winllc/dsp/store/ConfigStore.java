package com.winllc.dsp.store;

import com.winllc.dsp.cluster.Cluster;
import com.winllc.dsp.cluster.DatabaseProperties;
import com.winllc.dsp.cluster.SharedDocuments;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.model.CustomSchema;
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
    } else {
      SharedDocuments.Doc doc = shared.get(DOCUMENT);
      if (!doc.exists()) {
        StoreData initial = loadFile();
        doc = shared.createIfAbsent(DOCUMENT, mapper.writeValueAsString(initial));
        if (Files.exists(file)) log.info("Imported the portal configuration from {} into the shared database", file);
      }
      this.data = parse(doc.content());
      this.version = doc.version();
      this.checkedAt = System.currentTimeMillis();
    }
  }

  private StoreData loadFile() {
    if (!Files.exists(file)) return new StoreData();
    try {
      StoreData loaded = normalize(mapper.readValue(file.toFile(), StoreData.class));
      log.info("Loaded portal configuration from {}", file);
      return loaded;
    } catch (RuntimeException e) {
      throw new IllegalStateException("Could not read " + file + ": " + e.getMessage(), e);
    }
  }

  private StoreData parse(String json) {
    return normalize(mapper.readValue(json, StoreData.class));
  }

  private static StoreData normalize(StoreData loaded) {
    if (loaded.customSchema == null) loaded.customSchema = CustomSchema.empty();
    // Files written before dashboards and analyses existed.
    if (loaded.dashboards == null) loaded.dashboards = new java.util.ArrayList<>();
    if (loaded.analyses == null) loaded.analyses = new java.util.ArrayList<>();
    return loaded;
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
