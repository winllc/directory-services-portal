package com.winllc.dsp.analysis;

import com.winllc.dsp.util.LogSafe;
import com.winllc.dsp.cluster.Cluster;
import com.winllc.dsp.cluster.SharedDocuments;
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
 * With the shared database each result is the {@code analysis-result/<id>} document instead, so
 * every replica can show it; files from before are imported when first read.
 */
@Component
public class AnalysisResultStore {
  private static final Logger log = LoggerFactory.getLogger(AnalysisResultStore.class);
  private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

  /** A stored result and the version of the analysis settings it was computed with. */
  public record Stored(String analysisUpdatedAt, String ranBy, AnalysisResult result) {}

  private static final int CACHED = 4;

  private record Cached(Object modified, Stored stored) {}

  private final JsonMapper mapper;
  private final Path dir;
  private final Map<String, Cached> cache = new LinkedHashMap<>(8, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
      return size() > CACHED;
    }
  };

  private final SharedDocuments shared;

  public AnalysisResultStore(JsonMapper mapper, PortalProperties props, Cluster cluster) {
    this.mapper = mapper;
    this.dir = Path.of(props.getDataDir()).toAbsolutePath().resolve("analysis-results").normalize();
    this.shared = cluster.shared() ? cluster.documents() : null;
  }

  private static String document(String id) {
    if (id == null || !SAFE_ID.matcher(id).matches() || id.startsWith(".")) throw new IllegalArgumentException("Invalid analysis id");
    return "analysis-result/" + id;
  }

  /** The result file of an analysis: the id must be a plain name, and the file inside {@link #dir}. */
  private Path file(String id) {
    if (id == null || !SAFE_ID.matcher(id).matches() || id.startsWith(".")) throw new IllegalArgumentException("Invalid analysis id");
    Path f = dir.resolve(id + ".json").normalize();
    if (!f.startsWith(dir) || !f.getParent().equals(dir)) throw new IllegalArgumentException("Invalid analysis id");
    return f;
  }

  public void save(String id, Stored stored) {
    forget(id);
    if (shared != null) {
      file(id);
      shared.put(document(id), mapper.writeValueAsString(stored));
      return;
    }
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
    if (shared != null) return loadShared(id, f);
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
      log.warn("Could not read analysis result {}: {}", LogSafe.of(f), LogSafe.of(e.getMessage()));
      return null;
    } catch (RuntimeException e) {
      log.warn("Ignoring unreadable analysis result {}: {}", LogSafe.of(f), LogSafe.of(e.getMessage()));
      return null;
    }
  }

  public void delete(String id) {
    forget(id);
    if (shared != null) shared.delete(document(id));
    try {
      Files.deleteIfExists(file(id));
    } catch (IOException e) {
      log.warn("Could not delete the stored result of analysis {}: {}", LogSafe.of(id), LogSafe.of(e.getMessage()));
    }
  }

  private Stored loadShared(String id, Path f) {
    try {
      SharedDocuments.Doc doc = shared.get(document(id));
      if (!doc.exists() && Files.exists(f)) {
        // A result saved before the shared database: imported on first read.
        doc = shared.createIfAbsent(document(id), Files.readString(f));
      }
      if (!doc.exists()) {
        forget(id);
        return null;
      }
      synchronized (cache) {
        Cached c = cache.get(id);
        if (c != null && c.modified().equals(doc.version())) return c.stored();
      }
      Stored stored = mapper.readValue(doc.content(), Stored.class);
      synchronized (cache) {
        cache.put(id, new Cached(doc.version(), stored));
      }
      return stored;
    } catch (IOException | RuntimeException e) {
      log.warn("Could not read analysis result {}: {}", LogSafe.of(id), LogSafe.of(e.getMessage()));
      return null;
    }
  }

  private void forget(String id) {
    synchronized (cache) {
      cache.remove(id);
    }
  }
}
