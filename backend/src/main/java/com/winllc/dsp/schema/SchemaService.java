package com.winllc.dsp.schema;

import com.winllc.dsp.ldap.LdapDirectory;
import com.winllc.dsp.ldap.SchemaMapper;
import com.winllc.dsp.model.AttributeTypeDef;
import com.winllc.dsp.model.CustomSchema;
import com.winllc.dsp.model.ObjectClassDef;
import com.winllc.dsp.model.SchemaSnapshot;
import com.winllc.dsp.store.ConfigStore;
import com.winllc.dsp.store.StoreData;
import com.winllc.dsp.web.ApiException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Pulls the directory's schema, keeps portal-side custom definitions and merges the two
 * (custom definitions override server ones with the same OID or name).
 */
@Service
public class SchemaService {
  private static final Logger log = LoggerFactory.getLogger(SchemaService.class);

  private final LdapDirectory directory;
  private final ConfigStore store;
  private volatile Cached cached;

  private record Cached(StoreData stamp, SchemaIndex index) {}

  public SchemaService(LdapDirectory directory, ConfigStore store) {
    this.directory = directory;
    this.store = store;
  }

  /** Read the schema from the directory and cache it. */
  public SchemaSnapshot refresh() {
    SchemaSnapshot schema = directory.readSchema();
    store.update(d -> {
      d.schemaCache = schema;
      return null;
    });
    return schema;
  }

  public SchemaSnapshot serverSchema() {
    if (store.snapshot().schemaCache == null) {
      try {
        refresh();
      } catch (RuntimeException e) {
        log.warn("Could not read the schema from the directory: {}", e.getMessage());
      }
    }
    return store.snapshot().schemaCache;
  }

  /** Merged (server + custom) schema index; rebuilt only when the store changes. */
  public SchemaIndex mergedIndex() {
    serverSchema();
    StoreData snap = store.snapshot();
    Cached c = cached;
    if (c == null || c.stamp() != snap) {
      c = new Cached(snap, new SchemaIndex(merge(snap.schemaCache, snap.customSchema)));
      cached = c;
    }
    return c.index();
  }

  public CustomSchema custom() {
    return store.snapshot().customSchema;
  }

  static SchemaSnapshot merge(SchemaSnapshot server, CustomSchema custom) {
    List<AttributeTypeDef> attrs = mergeList(
        server == null ? List.of() : server.attributeTypes(), custom.attributeTypes(), a -> keys(a.oid(), a.names()));
    List<ObjectClassDef> classes = mergeList(
        server == null ? List.of() : server.objectClasses(), custom.objectClasses(), o -> keys(o.oid(), o.names()));
    return new SchemaSnapshot(attrs, classes, server == null ? null : server.fetchedAt(), server == null ? null : server.subschemaDn());
  }

  private static Set<String> keys(String oid, List<String> names) {
    Set<String> k = new HashSet<>();
    k.add(oid.toLowerCase());
    names.forEach(n -> k.add(n.toLowerCase()));
    return k;
  }

  private static <T> List<T> mergeList(List<T> base, List<T> over, Function<T, Set<String>> keyFn) {
    Set<String> overKeys = new HashSet<>();
    over.forEach(o -> overKeys.addAll(keyFn.apply(o)));
    List<T> out = new ArrayList<>();
    for (T b : base) if (keyFn.apply(b).stream().noneMatch(overKeys::contains)) out.add(b);
    out.addAll(over);
    return out;
  }

  private static <T> void upsert(List<T> list, T item, Function<T, Set<String>> keyFn) {
    Set<String> k = keyFn.apply(item);
    for (int i = 0; i < list.size(); i++) {
      if (keyFn.apply(list.get(i)).stream().anyMatch(k::contains)) {
        list.set(i, item);
        return;
      }
    }
    list.add(item);
  }

  public AttributeTypeDef upsertAttributeType(AttributeTypeDef def, String originalOid) {
    AttributeTypeDef item = def.withSource("custom");
    return store.update(d -> {
      List<AttributeTypeDef> list = d.customSchema.attributeTypes();
      if (originalOid != null) list.removeIf(x -> x.oid().equals(originalOid));
      upsert(list, item, a -> keys(a.oid(), a.names()));
      return item;
    });
  }

  public ObjectClassDef upsertObjectClass(ObjectClassDef def, String originalOid) {
    ObjectClassDef item = def.withSource("custom");
    return store.update(d -> {
      List<ObjectClassDef> list = d.customSchema.objectClasses();
      if (originalOid != null) list.removeIf(x -> x.oid().equals(originalOid));
      upsert(list, item, o -> keys(o.oid(), o.names()));
      return item;
    });
  }

  public void deleteAttributeType(String oid) {
    store.update(d -> {
      if (!d.customSchema.attributeTypes().removeIf(x -> x.oid().equals(oid))) throw ApiException.notFound("Custom schema element not found");
      return null;
    });
  }

  public void deleteObjectClass(String oid) {
    store.update(d -> {
      if (!d.customSchema.objectClasses().removeIf(x -> x.oid().equals(oid))) throw ApiException.notFound("Custom schema element not found");
      return null;
    });
  }

  public record ImportResult(int attributeTypes, int objectClasses, List<String> errors) {}

  /** Import schema text (OpenLDAP .schema, LDIF or raw descriptions) as custom definitions. */
  public ImportResult importText(String text) {
    SchemaMapper.ParsedText parsed = SchemaMapper.parseText(text, "custom");
    if (parsed.attributeTypes().isEmpty() && parsed.objectClasses().isEmpty()) {
      throw ApiException.badRequest(parsed.errors().isEmpty() ? "No attribute types or object classes found in the text" : parsed.errors().get(0));
    }
    store.update(d -> {
      parsed.attributeTypes().forEach(a -> upsert(d.customSchema.attributeTypes(), a, x -> keys(x.oid(), x.names())));
      parsed.objectClasses().forEach(o -> upsert(d.customSchema.objectClasses(), o, x -> keys(x.oid(), x.names())));
      return null;
    });
    return new ImportResult(parsed.attributeTypes().size(), parsed.objectClasses().size(), parsed.errors());
  }

  /** Custom definitions in OpenLDAP .schema syntax. */
  public String exportCustom() {
    StringBuilder sb = new StringBuilder("# Custom schema exported from Directory Services Portal\n");
    custom().attributeTypes().forEach(a -> sb.append("attributetype ").append(SchemaMapper.format(a)).append('\n'));
    custom().objectClasses().forEach(o -> sb.append("objectclass ").append(SchemaMapper.format(o)).append('\n'));
    return sb.toString();
  }
}
