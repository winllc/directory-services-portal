package com.winllc.dsp.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Plain-text mail through the SMTP server SMTP_HOST names (spring.mail.*); off while it is
 * empty, when messages are only logged. The last messages handed to it are kept in memory
 * (without their text), for the tests and for support.
 */
@Component
public class Mailer {
  private static final Logger log = LoggerFactory.getLogger(Mailer.class);
  private static final int KEEP = 100;

  /** A message as kept: recipients and subject, and whether it went out. */
  public record Sent(List<String> to, String subject, String text, boolean delivered) {}

  private final ObjectProvider<JavaMailSender> sender;
  private final String host;
  private final String from;
  private final Deque<Sent> recent = new ArrayDeque<>();

  public Mailer(ObjectProvider<JavaMailSender> sender, @Value("${spring.mail.host:}") String host,
      @Value("${portal.mail.from:}") String from) {
    this.sender = sender;
    this.host = host == null ? "" : host.trim();
    this.from = from == null || from.isBlank() ? "directory-portal@localhost" : from.trim();
  }

  public boolean enabled() {
    return !host.isEmpty() && sender.getIfAvailable() != null;
  }

  /** Sends one message; false when mail is off or the server refused it (logged). */
  public boolean send(List<String> to, String subject, String text) {
    boolean delivered = false;
    if (enabled() && !to.isEmpty()) {
      SimpleMailMessage m = new SimpleMailMessage();
      m.setFrom(from);
      m.setTo(to.toArray(String[]::new));
      m.setSubject(subject);
      m.setText(text);
      try {
        sender.getObject().send(m);
        delivered = true;
      } catch (MailException e) {
        log.warn("Could not send mail \"{}\": {}", subject, e.getMessage());
      }
    } else {
      log.info("Mail is not set up (SMTP_HOST): not sent: \"{}\" to {}", subject, to.size());
    }
    synchronized (recent) {
      recent.addLast(new Sent(List.copyOf(to), subject, text, delivered));
      while (recent.size() > KEEP) recent.removeFirst();
    }
    return delivered;
  }

  /** The last messages, oldest first. */
  public List<Sent> recent() {
    synchronized (recent) {
      return List.copyOf(recent);
    }
  }

  public void clear() {
    synchronized (recent) {
      recent.clear();
    }
  }
}
