package com.winllc.dsp.analysis;

import com.winllc.dsp.config.PortalProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The latest result of each saved analysis, kept in {@code <dataDir>/analysis-results/<id>.json}
 * so it can be reopened later (and survives restarts) without running the analysis again. The
 * few most recently read results stay in memory, since their findings are browsed page by page.
 */
@Component
public class AnalysisResultStore {
  private static final Logger log = LoggerFactory.getLogger(AnalysisResultStore.class);
  private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

  /** A stored result and the version of the analysis settings it was computed with. */
  public record Stored(String analysisUpdatedAt, String ranBy, AnalysisResult result) {}

  private static final int CACHED = 4;

  private record Cached(FileTime modified, Stored stored) {}

  private final JsonMapper mapper;
  private final Path dir;
  private final Map<String, Cached> cache = new LinkedHashMap<>(8, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
      return size() > CACHED;
    }
  };

  public AnalysisResultStore(JsonMapper mapper, PortalProperties props) {
    this.mapper = mapper;
    this.dir = Path.of(props.getDataDir()).toAbsolutePath().resolve("analysis-results");
  }

  private Path file(String id) {
    if (!SAFE_ID.matcher(id).matches() || id.startsWith(".")) throw new IllegalArgumentException("Invalid analysis id");
    return dir.resolve(id + ".json");
  }

  public void save(String id, Stored stored) {
    forget(id);
    try {
      Files.createDirectories(dir);
      Path target = file(id);
      Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
      mapper.writeValue(tmp.toFile(), stored);
      try {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Could not store the analysis result", e);
    }
  }

  public Stored load(String id) {
    Path f = file(id);
    try {
      if (!Files.exists(f)) {
        forget(id);
        return null;
      }
      FileTime modified = Files.getLastModifiedTime(f);
      synchronized (cache) {
        Cached c = cache.get(id);
        if (c != null && c.modified().equals(modified)) return c.stored();
      }
      Stored stored = mapper.readValue(f.toFile(), Stored.class);
      synchronized (cache) {
        cache.put(id, new Cached(modified, stored));
      }
      return stored;
    } catch (IOException e) {
      log.warn("Could not read analysis result {}: {}", f, e.getMessage());
      return null;
    } catch (RuntimeException e) {
      log.warn("Ignoring unreadable analysis result {}: {}", f, e.getMessage());
      return null;
    }
  }

  public void delete(String id) {
    forget(id);
    try {
      Files.deleteIfExists(file(id));
    } catch (IOException e) {
      log.warn("Could not delete the stored result of analysis {}", id, e);
    }
  }

  private void forget(String id) {
    synchronized (cache) {
      cache.remove(id);
    }
  }
}
