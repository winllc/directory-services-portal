package com.winllc.dsp.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchScope;
import com.winllc.dsp.ldap.DirectoryException;
import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.ldap.LdapDirectory;
import com.winllc.dsp.model.DirectoryEntry;
import com.winllc.dsp.model.DropdownSource;
import com.winllc.dsp.model.OptionItem;
import com.winllc.dsp.web.ApiException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Options of drop downs backed by a directory search ({@link DropdownSource.Ldap}): the full list
 * (list mode), a search for typed text (search mode, or narrowing a long list), and a lookup of
 * specific values (labels for stored values, and server-side validation that a submitted value
 * really is one of the options, however many there are).
 */
@Service
public class DirectoryOptions {
  /** Most typed words used in one search. */
  static final int MAX_WORDS = 5;
  /** Most values resolved by one lookup. */
  static final int MAX_LOOKUP = 200;
  private static final int SEARCH_CACHE_SIZE = 500;

  private final LdapDirectory directory;
  private final Map<String, Cached> cache = new LinkedHashMap<>(64, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
      return size() > SEARCH_CACHE_SIZE;
    }
  };

  private record Cached(long at, Page page) {}

  /**
   * Options found. {@code truncated}: more entries matched than {@code limit}. {@code filter}:
   * the LDAP filter that was used (shown when testing the settings).
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Page(List<OptionItem> options, boolean truncated, String filter) {}

  public DirectoryOptions(LdapDirectory directory) {
    this.directory = directory;
  }

  private static SearchScope scope(DropdownSource.Ldap src) {
    return "one".equals(src.scope()) ? SearchScope.ONE : SearchScope.SUB;
  }

  private static Filter baseFilter(DropdownSource.Ldap src) {
    try {
      return src.filter() == null || src.filter().isBlank() ? Filter.createPresenceFilter("objectClass") : Filter.create(src.filter().trim());
    } catch (LDAPException e) {
      throw ApiException.badRequest("The drop down's filter is invalid: " + e.getMessage());
    }
  }

  /** The filter for typed text: every word must match one of the search attributes. */
  public static Filter searchFilter(DropdownSource.Ldap src, String query) {
    Filter base = baseFilter(src);
    String q = query == null ? "" : query.trim();
    if (q.isEmpty()) return base;
    List<String> attrs = src.effectiveSearchAttributes().stream().filter(a -> !"dn".equalsIgnoreCase(a)).toList();
    List<Filter> parts = new ArrayList<>(List.of(base));
    for (String word : Arrays.stream(q.split("\\s+")).limit(MAX_WORDS).toList()) {
      List<Filter> any = new ArrayList<>();
      for (String a : attrs) {
        any.add(switch (src.matchOrDefault()) {
          case "exact" -> Filter.createEqualityFilter(a, word);
          case "startsWith" -> Filter.createSubstringFilter(a, word, null, null);
          default -> Filter.createSubstringFilter(a, null, new String[] {word}, null);
        });
      }
      parts.add(any.size() == 1 ? any.get(0) : Filter.createORFilter(any));
    }
    return Filter.createANDFilter(parts);
  }

  private static List<String> attributes(DropdownSource.Ldap src) {
    Set<String> out = new LinkedHashSet<>();
    out.add(src.labelAttribute());
    if (!src.valueIsDn()) out.add(src.valueAttribute());
    if (src.descriptionAttribute() != null) out.add(src.descriptionAttribute());
    if (src.sortAttribute() != null && !"dn".equalsIgnoreCase(src.sortAttribute())) out.add(src.sortAttribute());
    out.addAll(src.templateAttributes());
    out.removeIf(a -> a.isBlank() || EntryService.isHidden(a) || LdapDirectory.isBinary(a));
    return new ArrayList<>(out);
  }

  /** "{cn} ({mail})": placeholders replaced by the first value; empty brackets and separators dropped. */
  static String applyTemplate(String template, DirectoryEntry e) {
    var m = DropdownSource.Ldap.PLACEHOLDER.matcher(template);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String a = m.group(1).trim();
      String v = "dn".equalsIgnoreCase(a) ? e.dn() : e.first(a);
      m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(v == null ? "" : v));
    }
    m.appendTail(sb);
    return sb.toString()
        .replaceAll("\\(\\s*\\)|\\[\\s*]|<\\s*>", "")
        .replaceAll("\\s+([,;:])", "$1")
        .replaceAll("([,;:\\-–—|/])\\s*$", "")
        .replaceAll("^\\s*([,;:\\-–—|/])", "")
        .replaceAll("\\s{2,}", " ")
        .trim();
  }

  private static List<OptionItem> options(DropdownSource.Ldap src, List<DirectoryEntry> entries) {
    Map<String, OptionItem> out = new LinkedHashMap<>();
    Map<String, String> sortKeys = new LinkedHashMap<>();
    for (DirectoryEntry e : entries) {
      String label = src.labelTemplate() != null && !src.labelTemplate().isBlank() ? applyTemplate(src.labelTemplate(), e) : e.first(src.labelAttribute());
      String description = src.descriptionAttribute() == null ? null : e.first(src.descriptionAttribute());
      List<String> values = src.valueIsDn() ? List.of(e.dn()) : e.values(src.valueAttribute());
      for (String v : values) {
        OptionItem o = new OptionItem(v, label == null || label.isBlank() ? v : label, description);
        if (out.putIfAbsent(v, o) == null) {
          String key = src.sortAttribute() == null ? o.display()
              : "dn".equalsIgnoreCase(src.sortAttribute()) ? e.dn() : java.util.Objects.requireNonNullElse(e.first(src.sortAttribute()), "￿");
          sortKeys.put(v, key.toLowerCase(Locale.ROOT));
        }
      }
    }
    List<OptionItem> sorted = new ArrayList<>(out.values());
    sorted.sort(Comparator.comparing((OptionItem o) -> sortKeys.get(o.value())).thenComparing(OptionItem::display, String.CASE_INSENSITIVE_ORDER));
    return sorted;
  }

  private Page run(DropdownSource.Ldap src, Filter filter) {
    int limit = src.limitOrDefault();
    LdapDirectory.Result r = directory.search(src.baseDn(), scope(src), filter, limit, attributes(src));
    // The base entry itself is never an option of a sub-tree search.
    List<DirectoryEntry> entries = r.entries().stream().filter(e -> !Dns.equal(e.dn(), src.baseDn())).toList();
    return new Page(options(src, entries), r.truncated(), filter.toString());
  }

  private Page cached(String key, int seconds, java.util.function.Supplier<Page> load) {
    if (seconds > 0) {
      synchronized (cache) {
        Cached c = cache.get(key);
        if (c != null && System.currentTimeMillis() - c.at() < seconds * 1000L) return c.page();
      }
    }
    Page page = load.get();
    if (seconds > 0) {
      synchronized (cache) {
        cache.put(key, new Cached(System.currentTimeMillis(), page));
      }
    }
    return page;
  }

  /** All options (up to the limit). */
  public Page list(DropdownSource.Ldap src) {
    return cached(src + "\u0000", src.cacheSecondsOrDefault(), () -> run(src, baseFilter(src)));
  }

  /** Options matching typed text; fewer than {@code minChars} characters gives no options. */
  public Page search(DropdownSource.Ldap src, String query) {
    String q = query == null ? "" : query.trim();
    if (q.isEmpty() && !src.searchMode()) return list(src);
    if (q.length() < src.minCharsOrDefault()) return new Page(List.of(), false, null);
    // Searches are reused briefly (typing the same text again), at most as long as the list.
    int seconds = Math.min(src.cacheSecondsOrDefault(), 30);
    return cached(src + "\u0000" + q.toLowerCase(Locale.ROOT), seconds, () -> run(src, searchFilter(src, q)));
  }

  /**
   * The options for these values, as submitted (values that aren't options are left out). Used
   * to label stored values and to validate input without loading every option.
   */
  public List<OptionItem> lookup(DropdownSource.Ldap src, Collection<String> values) {
    List<String> wanted = values.stream().filter(v -> v != null && !v.isBlank()).distinct().limit(MAX_LOOKUP).toList();
    if (wanted.isEmpty()) return List.of();
    Filter base = baseFilter(src);
    Map<String, OptionItem> found = new LinkedHashMap<>();
    if (src.valueIsDn()) {
      for (String v : wanted) {
        if (!Dns.isValid(v)) continue;
        int depth = Dns.depthBelow(v, src.baseDn());
        if (depth < 1 || ("one".equals(src.scope()) && depth != 1)) continue;
        List<DirectoryEntry> hit;
        try {
          hit = directory.search(v, SearchScope.BASE, base, 1, attributes(src)).entries();
        } catch (DirectoryException e) {
          if (e.code() == DirectoryException.Code.NO_SUCH_OBJECT) continue;
          throw e;
        }
        if (!hit.isEmpty()) {
          OptionItem o = options(src, hit).get(0);
          found.put(v, new OptionItem(v, o.label(), o.description()));
        }
      }
      return new ArrayList<>(found.values());
    }
    Filter any = Filter.createORFilter(wanted.stream().map(v -> Filter.createEqualityFilter(src.valueAttribute(), v)).toList());
    List<DirectoryEntry> entries = directory.search(src.baseDn(), scope(src), Filter.createANDFilter(base, any), wanted.size() * 2 + 10,
        attributes(src)).entries();
    List<OptionItem> opts = options(src, entries);
    for (String v : wanted) {
      // The directory matched with the attribute's matching rule (usually case-insensitive).
      opts.stream().filter(o -> o.value().equalsIgnoreCase(v)).findFirst()
          .ifPresent(o -> found.put(v, new OptionItem(v, o.label(), o.description())));
    }
    return new ArrayList<>(found.values());
  }

  public void clearCache() {
    synchronized (cache) {
      cache.clear();
    }
  }
}
