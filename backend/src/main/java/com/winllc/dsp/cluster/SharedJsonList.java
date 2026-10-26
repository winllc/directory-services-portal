package com.winllc.dsp.cluster;

import com.winllc.dsp.util.LogSafe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A small list of records kept as one JSON document: alone, a file in the data directory (written
 * atomically, readable by the portal's user only); with the shared database, the named
 * {@code dsp_document}, read again whenever another replica changed it. A file written before the
 * database was configured is imported into it once. For settings-sized data (API tokens, webhooks).
 */
public final class SharedJsonList<T> {
  private static final Logger log = LoggerFactory.getLogger(SharedJsonList.class);

  private final String document;
  private final Path file;
  private final JsonMapper mapper;
  private final TypeReference<List<T>> type;
  private final SharedDocuments shared;
  private final List<T> items = new ArrayList<>();
  private long version;

  public SharedJsonList(String document, Path file, JsonMapper mapper, Cluster cluster, TypeReference<List<T>> type) {
    this.document = document;
    this.file = file;
    this.mapper = mapper;
    this.type = type;
    this.shared = cluster.shared() ? cluster.documents() : null;
    load();
  }

  private void load() {
    if (shared != null) {
      SharedDocuments.Doc doc = shared.get(document);
      if (!doc.exists() && Files.exists(file)) {
        try {
          doc = shared.createIfAbsent(document, mapper.writeValueAsString(parse(Files.readString(file, StandardCharsets.UTF_8))));
          log.info("Imported {} into the shared database", file);
        } catch (IOException | RuntimeException e) {
          log.error("Could not import {}: {}", file, LogSafe.of(e.getMessage()));
        }
      }
      use(doc);
      return;
    }
    if (!Files.exists(file)) return;
    try {
      items.addAll(parse(Files.readString(file, StandardCharsets.UTF_8)));
    } catch (IOException | RuntimeException e) {
      log.error("Could not read {}: {}; it is treated as empty until fixed", file, LogSafe.of(e.getMessage()));
    }
  }

  private List<T> parse(String json) {
    return new ArrayList<>(mapper.readValue(json, type));
  }

  private void use(SharedDocuments.Doc doc) {
    items.clear();
    if (doc.exists()) items.addAll(parse(doc.content()));
    version = doc.version();
  }

  /** With the shared database: reads the list again when another replica changed it. */
  private void sync() {
    if (shared == null) return;
    try {
      if (shared.version(document) != version) use(shared.get(document));
    } catch (RuntimeException e) {
      log.warn("Could not check the shared {} for changes: {}", document, LogSafe.of(e.getMessage()));
    }
  }

  /** Where the list is kept, for messages: the file, or the shared database's document. */
  public String describe() {
    return shared != null ? "the shared database (dsp_document " + document + ")" : file.toString();
  }

  /** The current items (a copy). */
  public synchronized List<T> list() {
    sync();
    return List.copyOf(items);
  }

  /** Applies a change to the latest items and stores them; on failure nothing changes. */
  public synchronized void change(Consumer<List<T>> change) throws IOException {
    if (shared != null) {
      try {
        use(shared.update(document, current -> {
          List<T> list = current == null ? new ArrayList<>() : parse(current);
          change.accept(list);
          return mapper.writeValueAsString(list);
        }));
      } catch (RuntimeException e) {
        throw new IOException("Could not store " + document + ": " + e.getMessage(), e);
      }
      return;
    }
    List<T> next = new ArrayList<>(items);
    change.accept(next);
    save(next);
    items.clear();
    items.addAll(next);
  }

  private void save(List<T> list) throws IOException {
    Files.createDirectories(file.getParent());
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    Files.deleteIfExists(tmp);
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }
    Files.writeString(tmp, mapper.writeValueAsString(list), StandardCharsets.UTF_8);
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }
}
