package com.winllc.dsp.store;

import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.model.CustomSchema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Persists portal configuration as a single JSON document ({@code <dataDir>/portal-config.json}).
 * Updates are serialized, applied to a deep copy (so a failing mutation changes nothing) and
 * written atomically (temp file + rename).
 */
@Component
public class ConfigStore {
  private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);

  private final JsonMapper mapper;
  private final Path file;
  private volatile StoreData data;

  public ConfigStore(JsonMapper mapper, PortalProperties props) {
    this.mapper = mapper;
    this.file = Path.of(props.getDataDir()).toAbsolutePath().resolve("portal-config.json");
    this.data = load();
  }

  private StoreData load() {
    if (!Files.exists(file)) return new StoreData();
    try {
      StoreData loaded = mapper.readValue(file.toFile(), StoreData.class);
      if (loaded.customSchema == null) loaded.customSchema = CustomSchema.empty();
      log.info("Loaded portal configuration from {}", file);
      return loaded;
    } catch (RuntimeException e) {
      throw new IllegalStateException("Could not read " + file + ": " + e.getMessage(), e);
    }
  }

  /** Current configuration. Treat as read-only; use {@link #update} to change it. */
  public StoreData snapshot() {
    return data;
  }

  public synchronized <T> T update(Function<StoreData, T> mutator) {
    StoreData draft = mapper.convertValue(data, StoreData.class);
    T result = mutator.apply(draft);
    persist(draft);
    data = draft;
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
