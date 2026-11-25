package com.winllc.dsp.groups;

import com.enssolutions.license.LicenseState;
import com.winllc.dsp.cluster.Cluster;
import com.winllc.dsp.license.LicensedFeature;
import com.winllc.dsp.license.Licensing;
import com.winllc.dsp.util.LogSafe;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Group access's scheduled work: closing the requests nobody answered in time, and removing the
 * members whose end date has passed. With the shared database one replica runs each at a time (a
 * cluster lock), and each request or membership is closed by one caller only. Like the portal's other scheduled work it pauses while the license is not in full
 * effect or doesn't cover group access.
 */
@Component
public class GroupAccessJobs {
  private static final Logger log = LoggerFactory.getLogger(GroupAccessJobs.class);

  private final GroupService groups;
  private final Cluster cluster;
  private final Licensing licensing;

  public GroupAccessJobs(GroupService groups, Cluster cluster, Licensing licensing) {
    this.groups = groups;
    this.cluster = cluster;
    this.licensing = licensing;
  }

  private boolean licensed() {
    try {
      return licensing.current().mode() == LicenseState.Mode.FULL
          && (licensing.allows(LicensedFeature.DIRECTORY_EDITOR) || licensing.allows(LicensedFeature.SELF_SERVICE_PROFILE));
    } catch (RuntimeException e) {
      return false;
    }
  }

  @Scheduled(fixedDelay = 15 * 60_000, initialDelay = 2 * 60_000)
  void scheduledRequestExpiry() {
    cluster.runLocked("group-request-expiry", Duration.ofMinutes(10), Duration.ofMinutes(1), () -> {
      try {
        int n = expireRequests(Instant.now());
        if (n > 0) log.info("Closed {} group access request(s) nobody answered in time", n);
      } catch (RuntimeException e) {
        log.error("Could not expire group access requests: {}", LogSafe.of(e.getMessage()));
      }
    });
  }

  /** Hourly: end dates are whole days, so a membership ends within the hour after its last day (UTC). */
  @Scheduled(fixedDelay = 60 * 60_000, initialDelay = 3 * 60_000)
  void scheduledMembershipExpiry() {
    cluster.runLocked("group-membership-expiry", Duration.ofMinutes(30), Duration.ofMinutes(1), () -> {
      try {
        int n = expireMemberships(Instant.now());
        if (n > 0) log.info("Removed {} group member(s) whose end date had passed", n);
      } catch (RuntimeException e) {
        log.error("Could not end time-limited group memberships: {}", LogSafe.of(e.getMessage()));
      }
    });
  }

  /** Removes the members whose end date has passed at {@code now} (see {@link GroupService#expireMemberships}); nothing while the license pauses jobs. */
  public int expireMemberships(Instant now) {
    return licensed() ? groups.expireMemberships(now) : 0;
  }

  /** Expires the requests due at {@code now} (see {@link GroupService#expireRequests}); nothing while the license pauses jobs. */
  public int expireRequests(Instant now) {
    return licensed() ? groups.expireRequests(now) : 0;
  }
}
