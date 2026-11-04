package com.winllc.dsp.web;

import com.winllc.dsp.ldap.Directories;
import com.winllc.dsp.license.LicensedFeature;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The LDAP directories the portal is configured with, for administrators (read only: they are
 * configured in the environment). Never a bind password.
 */
@RestController
@RequestMapping("/api/admin/directories")
public class AdminLdapDirectoriesController {
  private final Directories directories;

  public AdminLdapDirectoriesController(Directories directories) {
    this.directories = directories;
  }

  /**
   * One directory: {@code servers} in the order tried ({@code serverSelection} failover or
   * round-robin), {@code currentServer} the one the latest connection went to ("host:port"),
   * {@code reachable} whether it answered just now (null when not checked: {@code health=false}, or
   * disabled), {@code enabled} false when the license doesn't include multiple directories.
   */
  public record View(String id, String name, boolean primary, List<String> servers, String serverSelection, String currentServer,
      Boolean reachable, String error, boolean enabled, String baseDn, boolean x509, boolean sso) {}

  /** {@code multiDirectoryLicensed}: the license includes multi-directory; {@code notice}: why some are disabled. */
  public record Listing(List<View> directories, boolean multiDirectoryLicensed, String notice) {}

  @GetMapping
  public Listing list(@RequestParam(defaultValue = "true") boolean health) {
    List<View> out = new ArrayList<>();
    for (Directories.Directory d : directories.all()) {
      boolean enabled = directories.enabled(d);
      Boolean reachable = null;
      String error = null;
      if (health && enabled) {
        try {
          d.ldap().ping();
          reachable = true;
        } catch (RuntimeException e) {
          reachable = false;
          error = e.getMessage();
        }
      }
      out.add(new View(d.id(), d.name(), d.primary(), d.config().urlList(), d.config().roundRobin() ? "round-robin" : "failover",
          d.ldap().currentServer(), reachable, error, enabled, d.config().getBaseDn(), d.config().isX509(), d.config().isSso()));
    }
    boolean licensed = directories.multipleAllowed();
    String notice = !licensed && directories.all().size() > 1
        ? "Only the first directory is in use: the license doesn't include " + LicensedFeature.MULTI_DIRECTORY.label().toLowerCase()
          + " (" + LicensedFeature.MULTI_DIRECTORY.id() + "). The others are disabled: nobody can sign in to them, and their definitions"
          + " are not available."
        : null;
    return new Listing(out, licensed, notice);
  }
}
