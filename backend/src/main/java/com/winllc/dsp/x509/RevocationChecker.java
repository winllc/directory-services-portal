package com.winllc.dsp.x509;

import com.winllc.dsp.config.EnsProperties;
import com.winllc.dsp.config.PortalProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.CRL;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertStore;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.security.auth.x500.X500Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Revocation checking of client certificates (ENS_X509_REVOCATION: off, ocsp, crl or
 * ocsp-then-crl), configured per check: the OCSP response or the CRLs are fetched here, bounded
 * ({@link RevocationFetcher}), and verified by the JDK's PKIX validator with its own
 * {@link PKIXRevocationChecker} for this path only. No global JDK security property is set, and
 * the JDK never fetches anything itself.
 *
 * <ul>
 *   <li>OCSP asks ENS_X509_OCSP_RESPONDER_URL, else the certificate's AIA responder.
 *   <li>CRLs come from ENS_X509_CRL_DIR (DER or PEM files, read again when they change) when it is
 *       set, else from the certificate's http(s) distribution points (kept until their next
 *       update, at most a day).
 *   <li>A good or revoked answer is kept for ENS_X509_REVOCATION_CACHE per issuer and serial; an
 *       undetermined one is not. ENS_X509_REVOCATION_FAIL=open lets an undetermined certificate
 *       in with a warning.
 * </ul>
 */
@Component
public class RevocationChecker {
  private static final Logger log = LoggerFactory.getLogger(RevocationChecker.class);
  static final long MAX_OCSP_RESPONSE = 1024 * 1024;
  static final long MAX_CRL = 32L * 1024 * 1024;
  private static final Duration MAX_CRL_REUSE = Duration.ofDays(1);
  private static final int MAX_CACHED = 10_000;

  public enum Mode { OFF, OCSP, CRL, OCSP_THEN_CRL }

  public enum Status { GOOD, REVOKED, UNKNOWN }

  private record Answer(Status status, Instant until) {}

  private record FetchedCrl(X509CRL crl, Instant until) {}

  private record CrlFile(long modified, long size, List<X509CRL> crls) {}

  private final EnsProperties ens;
  private final PortalProperties props;
  private final RevocationFetcher fetcher;
  private final Map<String, Answer> answers = new ConcurrentHashMap<>();
  private final Map<URI, FetchedCrl> fetchedCrls = new ConcurrentHashMap<>();
  private final Map<Path, CrlFile> crlFiles = new ConcurrentHashMap<>();

  @Autowired
  public RevocationChecker(EnsProperties ens, PortalProperties props) {
    this(ens, props, new RevocationFetcher());
  }

  RevocationChecker(EnsProperties ens, PortalProperties props, RevocationFetcher fetcher) {
    this.ens = ens;
    this.props = props;
    this.fetcher = fetcher;
  }

  /** The mode in effect: ENS_X509_REVOCATION, or ocsp-then-crl while it is off and the older X509_CHECK_REVOCATION=true. */
  public Mode mode() {
    if (legacy()) return Mode.OCSP_THEN_CRL;
    return switch (ens.getX509().configuredMode()) {
      case "ocsp" -> Mode.OCSP;
      case "crl" -> Mode.CRL;
      case "ocsp-then-crl" -> Mode.OCSP_THEN_CRL;
      default -> Mode.OFF;
    };
  }

  /** Whether an undetermined status lets the certificate in; never with the older X509_CHECK_REVOCATION. */
  public boolean failOpen() {
    return !legacy() && ens.getX509().failOpen();
  }

  private boolean legacy() {
    String configured = ens.getX509().configuredMode();
    return (configured.isEmpty() || configured.equals("off")) && props.getX509().isCheckRevocation();
  }

  /** Drops cached answers and CRLs (tests, or after the CAs changed). */
  public void clearCache() {
    answers.clear();
    fetchedCrls.clear();
    crlFiles.clear();
  }

  /**
   * Checks the path's end-entity certificate, which {@code issuer} issued. Throws when it is
   * revoked, or when its status can't be determined and the check fails closed.
   *
   * @param path the leaf and any intermediates, as validated against {@code anchors}
   */
  public void check(List<X509Certificate> path, X509Certificate issuer, Set<TrustAnchor> anchors) {
    Mode mode = mode();
    if (mode == Mode.OFF) return;
    X509Certificate leaf = path.get(0);
    String key = leaf.getIssuerX500Principal().getName(X500Principal.RFC2253) + "|" + leaf.getSerialNumber().toString(16);
    Answer cached = answers.get(key);
    Status status = cached != null && Instant.now().isBefore(cached.until()) ? cached.status() : null;
    if (status == null) {
      status = switch (mode) {
        case OCSP -> ocsp(path, issuer, anchors);
        case CRL -> crl(path, issuer, anchors);
        case OCSP_THEN_CRL -> {
          Status s = ocsp(path, issuer, anchors);
          yield s == Status.UNKNOWN ? crl(path, issuer, anchors) : s;
        }
        case OFF -> Status.GOOD;
      };
      Duration keep = ens.getX509().getRevocationCache();
      if (status != Status.UNKNOWN && keep != null && !keep.isZero()) {
        if (answers.size() >= MAX_CACHED) answers.entrySet().removeIf(e -> !Instant.now().isBefore(e.getValue().until()));
        if (answers.size() < MAX_CACHED) answers.put(key, new Answer(status, Instant.now().plus(keep)));
      }
    }
    if (status == Status.REVOKED) throw CertificateRejectedException.revoked();
    if (status == Status.UNKNOWN) {
      if (!failOpen()) throw CertificateRejectedException.revocationUnknown();
      log.warn("The revocation status of {} (serial {}) could not be determined; allowed because ENS_X509_REVOCATION_FAIL=open",
          leaf.getSubjectX500Principal().getName(), leaf.getSerialNumber().toString(16));
    }
  }

  private Status ocsp(List<X509Certificate> path, X509Certificate issuer, Set<TrustAnchor> anchors) {
    X509Certificate leaf = path.get(0);
    String override = ens.getX509().getOcspResponderUrl();
    List<URI> responders = override != null && !override.isBlank() ? List.of(URI.create(override.trim())) : RevocationSources.ocspResponders(leaf);
    if (responders.isEmpty()) {
      log.debug("No OCSP responder for {}", leaf.getSubjectX500Principal());
      return Status.UNKNOWN;
    }
    byte[] request = RevocationSources.ocspRequest(leaf, issuer);
    for (URI responder : responders) {
      byte[] response;
      try {
        response = fetcher.fetch(responder, request, "application/ocsp-request", MAX_OCSP_RESPONSE);
      } catch (IOException e) {
        log.warn("OCSP responder {} did not answer: {}", responder, e.getMessage());
        continue;
      }
      Status status = validate(path, anchors, checker -> {
        checker.setOptions(EnumSet.of(PKIXRevocationChecker.Option.ONLY_END_ENTITY, PKIXRevocationChecker.Option.NO_FALLBACK));
        checker.setOcspResponses(Map.of(leaf, response));
      }, List.of());
      if (status != Status.UNKNOWN) return status;
    }
    return Status.UNKNOWN;
  }

  private Status crl(List<X509Certificate> path, X509Certificate issuer, Set<TrustAnchor> anchors) {
    X509Certificate leaf = path.get(0);
    List<X509CRL> crls = configuredCrlDir() != null ? crlsFromDirectory(leaf.getIssuerX500Principal()) : crlsFromDistributionPoints(leaf);
    if (crls.isEmpty()) {
      log.warn("No CRL for {} (issuer {})", leaf.getSubjectX500Principal().getName(), leaf.getIssuerX500Principal().getName());
      return Status.UNKNOWN;
    }
    return validate(path, anchors, checker -> checker.setOptions(EnumSet.of(PKIXRevocationChecker.Option.PREFER_CRLS,
        PKIXRevocationChecker.Option.ONLY_END_ENTITY, PKIXRevocationChecker.Option.NO_FALLBACK)), crls);
  }

  /** The PKIX validation of the path with a revocation checker set up for this check only. */
  private Status validate(List<X509Certificate> path, Set<TrustAnchor> anchors, java.util.function.Consumer<PKIXRevocationChecker> setup,
      Collection<? extends CRL> crls) {
    try {
      CertPathValidator validator = CertPathValidator.getInstance("PKIX");
      PKIXRevocationChecker checker = (PKIXRevocationChecker) validator.getRevocationChecker();
      setup.accept(checker);
      PKIXParameters params = new PKIXParameters(anchors);
      params.setRevocationEnabled(true);
      params.addCertPathChecker(checker);
      if (!crls.isEmpty()) params.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(crls)));
      validator.validate(CertificateFactory.getInstance("X.509").generateCertPath(path), params);
      return Status.GOOD;
    } catch (CertPathValidatorException e) {
      if (e.getReason() == CertPathValidatorException.BasicReason.REVOKED) return Status.REVOKED;
      log.warn("Revocation status of {} not determined: {}", path.get(0).getSubjectX500Principal().getName(), e.getMessage());
      return Status.UNKNOWN;
    } catch (GeneralSecurityException | RuntimeException e) {
      log.warn("Revocation status of {} not determined: {}", path.get(0).getSubjectX500Principal().getName(), e.getMessage());
      return Status.UNKNOWN;
    }
  }

  private Path configuredCrlDir() {
    String dir = ens.getX509().getCrlDir();
    return dir == null || dir.isBlank() ? null : Path.of(dir.trim());
  }

  /** The CRLs in ENS_X509_CRL_DIR for this issuer; files are parsed again when they change. */
  private List<X509CRL> crlsFromDirectory(X500Principal issuer) {
    Path dir = configuredCrlDir();
    List<X509CRL> out = new ArrayList<>();
    Map<Path, CrlFile> seen = new HashMap<>();
    try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
      for (Path f : files) {
        if (!Files.isRegularFile(f)) continue;
        try {
          long modified = Files.getLastModifiedTime(f).toMillis();
          long size = Files.size(f);
          CrlFile cached = crlFiles.get(f);
          if (cached == null || cached.modified() != modified || cached.size() != size) cached = new CrlFile(modified, size, parse(f));
          seen.put(f, cached);
          for (X509CRL crl : cached.crls()) if (crl.getIssuerX500Principal().equals(issuer)) out.add(crl);
        } catch (IOException | GeneralSecurityException e) {
          log.warn("Skipping {} in ENS_X509_CRL_DIR: {}", f, e.getMessage());
        }
      }
    } catch (IOException e) {
      log.warn("Cannot read ENS_X509_CRL_DIR {}: {}", dir, e.getMessage());
    }
    crlFiles.keySet().retainAll(seen.keySet());
    crlFiles.putAll(seen);
    return out;
  }

  private static List<X509CRL> parse(Path file) throws IOException, GeneralSecurityException {
    try (InputStream in = Files.newInputStream(file)) {
      List<X509CRL> out = new ArrayList<>();
      for (CRL crl : CertificateFactory.getInstance("X.509").generateCRLs(in)) if (crl instanceof X509CRL x) out.add(x);
      return out;
    }
  }

  /** CRLs from the certificate's http(s) distribution points, reused until their next update. */
  private List<X509CRL> crlsFromDistributionPoints(X509Certificate leaf) {
    List<X509CRL> out = new ArrayList<>();
    for (URI dp : RevocationSources.crlDistributionPoints(leaf)) {
      FetchedCrl cached = fetchedCrls.get(dp);
      if (cached == null || !Instant.now().isBefore(cached.until())) {
        try {
          byte[] der = fetcher.fetch(dp, null, null, MAX_CRL);
          X509CRL crl = (X509CRL) CertificateFactory.getInstance("X.509").generateCRL(new ByteArrayInputStream(der));
          Instant until = Instant.now().plus(MAX_CRL_REUSE);
          if (crl.getNextUpdate() != null && crl.getNextUpdate().toInstant().isBefore(until)) until = crl.getNextUpdate().toInstant();
          cached = new FetchedCrl(crl, until);
          if (Instant.now().isBefore(until)) fetchedCrls.put(dp, cached);
        } catch (IOException | GeneralSecurityException | ClassCastException e) {
          log.warn("CRL distribution point {} could not be read: {}", dp, e.getMessage());
          continue;
        }
      }
      out.add(cached.crl());
    }
    return out;
  }
}
