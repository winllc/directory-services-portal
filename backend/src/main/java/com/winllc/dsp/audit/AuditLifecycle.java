package com.winllc.dsp.audit;

import com.enssolutions.license.LicenseCheck;
import com.winllc.dsp.license.Licensing;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * System events: {@code system.started} and {@code system.stopped}; {@code license.mode-changed}
 * whenever the license's mode differs from the one last recorded (checked at startup, every minute
 * and after an install); and the daily retention job ({@code audit.pruned}, {@code job.completed}).
 */
@Component
public class AuditLifecycle {
  private static final Logger log = LoggerFactory.getLogger(AuditLifecycle.class);

  private final AuditLog audit;
  private final AuditDelivery delivery;
  private final AuditProperties props;
  private final ObjectProvider<Licensing> licensing;
  private final AtomicBoolean started = new AtomicBoolean();

  public AuditLifecycle(AuditLog audit, AuditDelivery delivery, AuditProperties props, ObjectProvider<Licensing> licensing) {
    this.audit = audit;
    this.delivery = delivery;
    this.props = props;
    this.licensing = licensing;
  }

  @EventListener(ApplicationReadyEvent.class)
  void started() {
    if (!started.compareAndSet(false, true)) return;
    audit.event("system.started").system().detail("version", audit.version())
        .detail("retentionDays", props.getRetentionDays()).success();
    checkLicenseMode();
  }

  @PreDestroy
  void stopped() {
    if (!started.get()) return;
    audit.event("system.stopped").system().detail("version", audit.version()).success();
  }

  /** Records {@code license.mode-changed} when the mode differs from the last one recorded. */
  @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
  public synchronized void checkLicenseMode() {
    Licensing l = licensing.getIfAvailable();
    if (l == null) return;
    LicenseCheck check;
    try {
      check = l.current();
    } catch (RuntimeException e) {
      return;
    }
    String mode = check.mode().name();
    AuditState state = delivery.state();
    String previous = state.licenseMode();
    if (mode.equals(previous)) return;
    audit.event("license.mode-changed").target("license", Licensing.PRODUCT, "License")
        .detail("mode.old", previous).detail("mode.new", mode).detail("state", check.state().name())
        .detail("message", check.message()).success();
    try {
      state.setLicenseMode(mode);
    } catch (IOException e) {
      log.error("Could not save the recorded license mode: {}", e.getMessage());
    }
  }

  /** Daily at 03:17: prunes per {@code ENS_AUDIT_RETENTION_DAYS} (0 keeps everything). */
  @Scheduled(cron = "0 17 3 * * *")
  public void retentionJob() {
    if (props.getRetentionDays() <= 0) return;
    prune(Instant.now().minus(Duration.ofDays(props.getRetentionDays())));
  }

  /**
   * Deletes events older than {@code cutoff} that every enabled output has delivered, never the
   * newest one; records {@code audit.pruned} (when anything went) and {@code job.completed}.
   */
  public AuditStore.Pruned prune(Instant cutoff) {
    long began = System.nanoTime();
    long delivered = delivery.deliveredByAll();
    String cut = AuditLog.TIME.format(cutoff);
    try {
      AuditStore.Pruned p = audit.store().prune(s -> s.seq() <= delivered && s.time() != null && s.time().compareTo(cut) < 0);
      if (p.count() > 0) {
        audit.event("audit.pruned").system().target("audit-log", Licensing.PRODUCT, "Audit log")
            .detail("count", p.count()).detail("fromSeq", p.fromSeq()).detail("toSeq", p.toSeq())
            .detail("fromTime", p.fromTime()).detail("toTime", p.toTime()).detail("retentionDays", props.getRetentionDays())
            .success();
      }
      audit.event("job.completed").system().target("job", "audit-retention", "Audit retention")
          .detail("count", p.count()).detail("durationMs", (System.nanoTime() - began) / 1_000_000).success();
      return p;
    } catch (IOException | RuntimeException e) {
      log.error("Audit retention failed: {}", e.getMessage(), e);
      audit.event("job.failed").system().target("job", "audit-retention", "Audit retention")
          .detail("durationMs", (System.nanoTime() - began) / 1_000_000).failed(e.getMessage());
      return new AuditStore.Pruned(0, null, null, null, null);
    }
  }
}
