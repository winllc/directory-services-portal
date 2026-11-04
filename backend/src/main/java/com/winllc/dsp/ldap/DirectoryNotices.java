package com.winllc.dsp.ldap;

import com.winllc.dsp.license.LicensedFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Says at startup which configured directories the license leaves disabled (nothing fails to start). */
@Component
public class DirectoryNotices {
  private static final Logger log = LoggerFactory.getLogger(DirectoryNotices.class);

  private final Directories directories;

  public DirectoryNotices(Directories directories) {
    this.directories = directories;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void report() {
    if (directories.all().size() < 2 || directories.multipleAllowed()) return;
    for (Directories.Directory d : directories.all()) {
      if (d.primary()) continue;
      log.warn("Directory {} \"{}\" is DISABLED: the license doesn't include {} ({}), so only the first directory, {}, is in use."
          + " Install a license with it to use the others.", d.id(), d.name(), LicensedFeature.MULTI_DIRECTORY.label(),
          LicensedFeature.MULTI_DIRECTORY.id(), directories.primary().id());
    }
  }
}
