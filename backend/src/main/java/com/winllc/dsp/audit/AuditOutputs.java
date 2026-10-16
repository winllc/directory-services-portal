package com.winllc.dsp.audit;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The synchronous outputs, both on the {@code ens.audit} logger, which gets its own appenders and
 * {@code additivity=false} so the main log format never wraps an event:
 *
 * <ul>
 *   <li>stdout ({@code ENS_AUDIT_STDOUT}, on by default): one raw JSON line per event, for Docker
 *       logging drivers and the Splunk Universal Forwarder;
 *   <li>a file ({@code ENS_AUDIT_FILE}, off when empty): rolled daily and at 100 MB, keeping
 *       {@code ENS_AUDIT_FILE_KEEP} days of files (default 30).
 * </ul>
 *
 * The monthly store files are not this file: they are never rotated or deleted (except by
 * retention), so they cannot double as the rotating output.
 */
@Component
public class AuditOutputs {
  private static final Logger log = LoggerFactory.getLogger(AuditOutputs.class);
  static final String STDOUT_APPENDER = "ENS_AUDIT_STDOUT";
  static final String FILE_APPENDER = "ENS_AUDIT_FILE";

  private final AuditProperties props;
  private final boolean stdout;
  private final String file;

  public AuditOutputs(AuditProperties props) {
    this.props = props;
    this.stdout = props.isStdout();
    this.file = props.getFile() == null || props.getFile().isBlank() ? null : props.getFile().trim();
    configure();
  }

  public boolean stdoutEnabled() {
    return stdout;
  }

  public String file() {
    return file;
  }

  private void configure() {
    if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx)) {
      log.warn("Logback is not the logging backend: the {} logger keeps the application's log configuration", AuditLog.LOGGER);
      return;
    }
    ch.qos.logback.classic.Logger logger = ctx.getLogger(AuditLog.LOGGER);
    logger.setAdditive(false);
    List<Appender<ILoggingEvent>> old = new ArrayList<>();
    for (Iterator<Appender<ILoggingEvent>> it = logger.iteratorForAppenders(); it.hasNext(); ) old.add(it.next());
    for (Appender<ILoggingEvent> a : old) {
      if (a.getName() != null && a.getName().startsWith("ENS_AUDIT_")) {
        logger.detachAppender(a);
        a.stop();
      }
    }
    if (stdout) {
      ConsoleAppender<ILoggingEvent> console = new ConsoleAppender<>();
      console.setContext(ctx);
      console.setName(STDOUT_APPENDER);
      console.setEncoder(encoder(ctx));
      console.start();
      logger.addAppender(console);
    }
    if (file != null) {
      try {
        Path p = Path.of(file).toAbsolutePath();
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        RollingFileAppender<ILoggingEvent> rolling = new RollingFileAppender<>();
        rolling.setContext(ctx);
        rolling.setName(FILE_APPENDER);
        rolling.setFile(p.toString());
        rolling.setEncoder(encoder(ctx));
        SizeAndTimeBasedRollingPolicy<ILoggingEvent> policy = new SizeAndTimeBasedRollingPolicy<>();
        policy.setContext(ctx);
        policy.setParent(rolling);
        String base = p.toString().endsWith(".jsonl") ? p.toString().substring(0, p.toString().length() - 6) : p.toString();
        policy.setFileNamePattern(base + ".%d{yyyy-MM-dd}.%i.jsonl");
        policy.setMaxFileSize(FileSize.valueOf("100MB"));
        policy.setMaxHistory(Math.max(props.getFileKeep(), 1));
        policy.start();
        rolling.setRollingPolicy(policy);
        rolling.start();
        logger.addAppender(rolling);
      } catch (IOException | RuntimeException e) {
        log.error("Could not open the audit file {}: {}", file, e.getMessage());
      }
    }
  }

  private static PatternLayoutEncoder encoder(LoggerContext ctx) {
    PatternLayoutEncoder e = new PatternLayoutEncoder();
    e.setContext(ctx);
    e.setPattern("%msg%n");
    e.start();
    return e;
  }
}
