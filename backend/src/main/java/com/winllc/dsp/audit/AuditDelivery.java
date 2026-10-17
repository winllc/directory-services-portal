package com.winllc.dsp.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit outputs and their delivery state: stdout and the file (synchronous, see
 * {@link AuditOutputs}), and the background forwarders to Splunk HEC and syslog with their cursors
 * in {@link AuditState}. Logs one line per output at startup (never the HEC token).
 */
@Component
public class AuditDelivery implements AuditForwarder.Listener {
  private static final Logger log = LoggerFactory.getLogger(AuditDelivery.class);

  /** One row of the delivery status panel. */
  public record OutputStatus(String output, boolean enabled, boolean background, String destination, Long lastDeliveredSeq,
      String lastDeliveredAt, Long backlog, String lastError, String lastErrorAt, String failingSince) {}

  private final AuditLog audit;
  private final AuditProperties props;
  private final AuditOutputs outputs;
  private final AuditState state;
  private final List<AuditForwarder> forwarders = new ArrayList<>();
  private final ObjectProvider<MeterRegistry> meters;

  public AuditDelivery(AuditLog audit, AuditProperties props, AuditOutputs outputs, JsonMapper mapper, ObjectProvider<MeterRegistry> meters) {
    this.audit = audit;
    this.meters = meters;
    this.props = props;
    this.outputs = outputs;
    this.state = new AuditState(audit.store().dir(), mapper);
    if (props.getSplunk().enabled()) {
      forwarders.add(new SplunkHecForwarder(props.getSplunk(), audit.host(), audit.product(), audit.store(), state, this));
    }
    if (props.getSyslog().enabled()) {
      forwarders.add(new SyslogForwarder(props.getSyslog(), audit.host(), audit.product(), audit.store(), state, this));
    }
  }

  public AuditState state() {
    return state;
  }

  @EventListener(ApplicationReadyEvent.class)
  void start() {
    log.info("Audit output stdout: {}", outputs.stdoutEnabled() ? "enabled (ens.audit logger)" : "disabled");
    log.info("Audit output file: {}", outputs.file() != null ? "enabled, " + outputs.file() + " (keep " + props.getFileKeep() + ")" : "disabled");
    SplunkHecForwarder splunk = (SplunkHecForwarder) forwarders.stream().filter(f -> f instanceof SplunkHecForwarder).findFirst().orElse(null);
    if (splunk == null) {
      log.info("Audit output splunk: disabled");
    } else {
      log.info("Audit output splunk: enabled, {} (sourcetype {}{}){}", splunk.destination(), props.getSplunk().getSourcetype(),
          props.getSplunk().getIndex().isBlank() ? "" : ", index " + props.getSplunk().getIndex(), splunk.hasToken() ? "" : "; NO TOKEN configured");
      if (props.getSplunk().isInsecure()) {
        log.warn("ENS_AUDIT_SPLUNK_INSECURE is true: the HEC server's certificate is NOT verified (trust-all). This is not FIPS 140-3"
            + " or DISA STIG appropriate; use it only in test setups.");
      }
    }
    AuditForwarder syslog = forwarders.stream().filter(f -> f instanceof SyslogForwarder).findFirst().orElse(null);
    log.info("Audit output syslog: {}", syslog == null ? "disabled" : "enabled, " + syslog.destination() + " (facility "
        + props.getSyslog().getFacility() + ")");
    forwarders.forEach(AuditForwarder::start);
  }

  @PreDestroy
  void stop() {
    forwarders.forEach(AuditForwarder::stop);
  }

  /** The lowest seq every enabled background output has delivered (the head when there are none). */
  public long deliveredByAll() {
    long min = audit.store().headSeq();
    for (AuditForwarder f : forwarders) min = Math.min(min, f.cursor());
    return min;
  }

  public List<OutputStatus> status() {
    long head = audit.store().headSeq();
    List<OutputStatus> out = new ArrayList<>();
    out.add(new OutputStatus("stdout", outputs.stdoutEnabled(), false, "ens.audit logger", outputs.stdoutEnabled() ? head : null,
        null, outputs.stdoutEnabled() ? 0L : null, null, null, null));
    out.add(new OutputStatus("file", outputs.file() != null, false, outputs.file(), outputs.file() != null ? head : null,
        null, outputs.file() != null ? 0L : null, null, null, null));
    for (String name : List.of("splunk", "syslog")) {
      AuditForwarder f = forwarders.stream().filter(x -> x.name().equals(name)).findFirst().orElse(null);
      if (f == null) {
        out.add(new OutputStatus(name, false, true, null, null, null, null, null, null, null));
      } else {
        AuditForwarder.Status s = f.status();
        out.add(new OutputStatus(name, true, true, s.destination(), s.lastDeliveredSeq(), s.lastDeliveredAt(), s.backlog(),
            s.lastError(), s.lastErrorAt(), s.failingSince()));
      }
    }
    return out;
  }

  @Override
  public void failed(String output, String error, Instant since) {
    audit.event("audit.delivery-failed").system().severity(AuditEvent.ALERT).target("audit-output", output, output)
        .detail("output", output).detail("error", error).detail("failingSince", since.toString()).failed(error);
  }

  @Override
  public void attemptFailed(String output) {
    MeterRegistry registry = meters.getIfAvailable();
    if (registry != null) {
      Counter.builder("ens.audit.forward.failures").description("Failed audit deliveries to Splunk HEC or syslog")
          .tag("output", output).register(registry).increment();
    }
  }

  @Override
  public void recovered(String output, Instant failingSince) {
    audit.event("audit.delivery-recovered").system().severity(AuditEvent.NOTICE).target("audit-output", output, output)
        .detail("output", output).detail("failingSince", failingSince == null ? null : failingSince.toString()).success();
  }
}
