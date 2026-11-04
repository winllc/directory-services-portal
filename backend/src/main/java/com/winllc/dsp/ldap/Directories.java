package com.winllc.dsp.ldap;

import com.winllc.dsp.config.DirectoryProperties;
import com.winllc.dsp.license.LicenseRequiredException;
import com.winllc.dsp.license.LicensedFeature;
import com.winllc.dsp.license.Licensing;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.web.ApiException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * The LDAP directories the portal connects to, in configured order. The first is the primary one:
 * it is always in use, and it is the directory of everything stored before there were several
 * (definitions and grants without a directory, sessions and API tokens of earlier versions), and of
 * the id {@value DirectoryProperties#DEFAULT_ID} when no directory has that id.
 *
 * <p>More than one directory is the licensed feature {@link LicensedFeature#MULTI_DIRECTORY}: without
 * it only the primary directory is enabled, the others are listed as disabled and nothing reaches
 * them. Several servers for one directory (failover) are not licensed separately.
 */
public class Directories implements AutoCloseable {

  /** One configured directory: its settings, its connection gateway, and the demo server behind it in memory mode. */
  public record Directory(int index, DirectoryProperties config, LdapDirectory ldap, EmbeddedDirectory embedded) {
    public String id() {
      return config.getId();
    }

    public String name() {
      return config.displayName();
    }

    public boolean primary() {
      return index == 0;
    }
  }

  private final List<Directory> all;
  private final Licensing licensing;

  /** {@code licensing} null: every directory is enabled (tests). */
  public Directories(List<Directory> directories, Licensing licensing) {
    if (directories.isEmpty()) throw new IllegalArgumentException("At least one directory is required");
    this.all = List.copyOf(directories);
    this.licensing = licensing;
  }

  /**
   * Problems with a list of directory settings (empty when valid): each directory's own, and ids
   * that appear twice. Never includes a password.
   */
  public static List<String> problems(List<DirectoryProperties> list, String prefix) {
    List<String> out = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < list.size(); i++) {
      DirectoryProperties d = list.get(i);
      String where = prefix + "[" + i + "]" + (d.getId() == null || d.getId().isBlank() ? "" : " (" + d.getId() + ")");
      out.addAll(d.problems(where));
      if (d.getId() != null && !d.getId().isBlank() && !ids.add(d.getId().toLowerCase(Locale.ROOT))) {
        out.add(where + ": the id " + d.getId() + " is used by more than one directory");
      }
      if (d.roundRobin()) {
        long schemes = d.urlList().stream().map(u -> u.toLowerCase(Locale.ROOT).startsWith("ldaps:") ? "ldaps" : "ldap").distinct().count();
        if (schemes > 1) out.add(where + ": round-robin needs every URL to use the same scheme (all ldap:// or all ldaps://)");
      }
    }
    return out;
  }

  public List<Directory> all() {
    return all;
  }

  public Directory primary() {
    return all.get(0);
  }

  /** Whether more than one directory may be used now (the license includes multi-directory). */
  public boolean multipleAllowed() {
    return licensing == null || licensing.allows(LicensedFeature.MULTI_DIRECTORY);
  }

  public boolean enabled(Directory d) {
    return d.primary() || multipleAllowed();
  }

  /** The directories in use now, in order. */
  public List<Directory> enabled() {
    return multipleAllowed() ? all : List.of(primary());
  }

  /** More than one directory is configured and enabled (sign-in then asks which). */
  public boolean several() {
    return enabled().size() > 1;
  }

  /**
   * A configured directory, enabled or not: by id (ignoring case), the primary one for null or
   * blank, and for {@value DirectoryProperties#DEFAULT_ID} when no directory has that id.
   */
  public Optional<Directory> find(String id) {
    if (id == null || id.isBlank()) return Optional.of(primary());
    String wanted = id.trim();
    for (Directory d : all) if (d.id().equalsIgnoreCase(wanted)) return Optional.of(d);
    if (wanted.equalsIgnoreCase(DirectoryProperties.DEFAULT_ID)) return Optional.of(primary());
    return Optional.empty();
  }

  /** The id {@code id} stands for (see {@link #find}); unknown ids are returned as given. */
  public String canonicalId(String id) {
    return find(id).map(Directory::id).orElse(id == null ? null : id.trim());
  }

  /** Whether two directory references name the same directory. */
  public boolean same(String a, String b) {
    String x = canonicalId(a);
    String y = canonicalId(b);
    return x != null && x.equalsIgnoreCase(y);
  }

  /** Whether the directory exists and is enabled. */
  public boolean available(String id) {
    return find(id).filter(this::enabled).isPresent();
  }

  /**
   * An enabled directory.
   *
   * @throws ApiException 404 when no directory has that id
   * @throws LicenseRequiredException when it is configured but disabled (the license doesn't include multi-directory)
   */
  public Directory require(String id) {
    Directory d = find(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No LDAP directory \"" + id + "\" is configured",
        "unknown_directory"));
    if (!enabled(d)) throw LicenseRequiredException.feature(licensing.current(), LicensedFeature.MULTI_DIRECTORY);
    return d;
  }

  /** The directory a definition reads and writes (see {@link #require}). */
  public Directory forDefinition(DirectoryDefinition def) {
    return require(def.directory());
  }

  /** The directory a user signed in to (see {@link #require}); the primary one for sessions of earlier versions. */
  public Directory forUser(SessionUser user) {
    return require(user.directory());
  }

  @Override
  public void close() {
    for (Directory d : all) {
      try {
        d.ldap().close();
      } finally {
        if (d.embedded() != null) d.embedded().close();
      }
    }
  }
}
