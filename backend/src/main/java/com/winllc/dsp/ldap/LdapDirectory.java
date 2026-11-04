package com.winllc.dsp.ldap;

import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.BindRequest;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.ExtendedResult;
import com.unboundid.ldap.sdk.FailoverServerSet;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPSearchException;
import com.unboundid.ldap.sdk.LDAPURL;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.PostConnectProcessor;
import com.unboundid.ldap.sdk.RDN;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.RoundRobinServerSet;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchResultListener;
import com.unboundid.ldap.sdk.SearchResultReference;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.ServerSet;
import com.unboundid.ldap.sdk.SimpleBindRequest;
import com.unboundid.ldap.sdk.SingleServerSet;
import com.unboundid.ldap.sdk.StartTLSPostConnectProcessor;
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl;
import com.unboundid.ldap.sdk.extensions.StartTLSExtendedRequest;
import com.unboundid.ldap.sdk.schema.Schema;
import com.unboundid.util.ssl.HostNameSSLSocketVerifier;
import com.unboundid.util.ssl.JVMDefaultTrustManager;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import com.winllc.dsp.model.DirectoryEntry;
import com.winllc.dsp.model.SchemaSnapshot;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gateway to the directory server. All reads and writes use a pooled connection bound as the
 * service account; the portal enforces its own per-definition permissions on top. User
 * passwords are verified with a separate, short-lived bind.
 *
 * <p>With a meter registry, every operation is timed in
 * {@code ens_dsp_ldap_operation_seconds{operation,outcome}} (outcome {@code failure} when it threw)
 * and {@code ens_dsp_ldap_pool_available} gauges the pool's idle connections.
 */
public class LdapDirectory implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(LdapDirectory.class);
  private static final Set<String> BINARY_ATTRIBUTES =
      Set.of("jpegphoto", "thumbnailphoto", "usercertificate", "usercertificate;binary", "objectguid", "objectsid", "photo");

  /**
   * Connection settings. {@code urls}: one or more servers holding the same data, tried in order
   * ({@link FailoverServerSet}) or in turn ({@code roundRobin}, {@link RoundRobinServerSet}); all
   * use the same scheme's TLS settings. {@code id} names the directory in metrics and the pool.
   */
  public record Settings(String id, List<String> urls, boolean roundRobin, String bindDn, String bindPassword, boolean startTls,
      boolean tlsRejectUnauthorized, int timeoutMs, int poolSize) {

    /** One server (the settings of the first version). */
    public Settings(String url, String bindDn, String bindPassword, boolean startTls, boolean tlsRejectUnauthorized, int timeoutMs, int poolSize) {
      this("default", List.of(url), false, bindDn, bindPassword, startTls, tlsRejectUnauthorized, timeoutMs, poolSize);
    }

    @Override
    public String toString() {
      // Never the password.
      return "Settings[" + id + ", " + urls + "]";
    }
  }

  public record Result(List<DirectoryEntry> entries, boolean truncated) {}

  /** Outcome of {@link #scan}: how many entries were delivered, and whether more were left out. */
  public record ScanResult(int entries, boolean truncated) {}

  private static final int SCAN_PAGE_SIZE = 500;
  private static final long FAILOVER_CONNECTION_AGE_MILLIS = 15 * 60_000L;

  private final Settings settings;
  private final ServerSet serverSet;
  /** The server the last connection was made to ("host:port"), for the administrators' view. */
  private volatile String lastServer;
  private final SSLContext startTlsContext;
  private final MeterRegistry meters;
  private volatile LDAPConnectionPool pool;

  public LdapDirectory(Settings settings) {
    this(settings, null);
  }

  public LdapDirectory(Settings settings, MeterRegistry meters) {
    this.settings = settings;
    this.meters = meters;
    if (meters != null) {
      Gauge.builder("ens.dsp.ldap.pool.available", this, d -> d.pool == null ? 0 : Math.max(0, d.pool.getCurrentAvailableConnections()))
          .description("Idle connections in the LDAP connection pool").tag("directory", settings.id()).register(meters);
    }
    if (settings.urls() == null || settings.urls().isEmpty()) throw new IllegalArgumentException("No LDAP server URL for directory " + settings.id());
    try {
      List<LDAPURL> urls = new ArrayList<>();
      for (String u : settings.urls()) urls.add(new LDAPURL(u.trim()));
      SSLUtil ssl = new SSLUtil(settings.tlsRejectUnauthorized() ? JVMDefaultTrustManager.getInstance() : new TrustAllTrustManager());
      LDAPConnectionOptions options = new LDAPConnectionOptions();
      options.setConnectTimeoutMillis(settings.timeoutMs());
      options.setResponseTimeoutMillis(settings.timeoutMs());
      boolean anyLdaps = urls.stream().anyMatch(u -> "ldaps".equalsIgnoreCase(u.getScheme()));
      if ((anyLdaps || settings.startTls()) && settings.tlsRejectUnauthorized()) {
        options.setSSLSocketVerifier(new HostNameSSLSocketVerifier(true));
      }
      List<ServerSet> servers = new ArrayList<>();
      for (LDAPURL url : urls) {
        boolean ldaps = "ldaps".equalsIgnoreCase(url.getScheme());
        SocketFactory socketFactory = ldaps ? ssl.createSSLSocketFactory() : SocketFactory.getDefault();
        servers.add(new SingleServerSet(url.getHost(), url.getPort(), socketFactory, options));
      }
      if (servers.size() == 1) {
        this.serverSet = servers.get(0);
      } else if (settings.roundRobin()) {
        String[] hosts = urls.stream().map(LDAPURL::getHost).toArray(String[]::new);
        int[] ports = urls.stream().mapToInt(LDAPURL::getPort).toArray();
        // One socket factory for all: round robin needs a single scheme (checked at startup).
        boolean ldaps = "ldaps".equalsIgnoreCase(urls.get(0).getScheme());
        this.serverSet = new RoundRobinServerSet(hosts, ports, ldaps ? ssl.createSSLSocketFactory() : SocketFactory.getDefault(), options);
      } else {
        FailoverServerSet failover = new FailoverServerSet(servers.toArray(ServerSet[]::new));
        // Tried in order for every new connection, so the first server is used again once it is back.
        failover.setReOrderOnFailover(false);
        // Pooled connections to a later server are closed after a while, so the pool goes back to
        // the first one once it answers again.
        failover.setMaxFailoverConnectionAgeMillis(FAILOVER_CONNECTION_AGE_MILLIS);
        this.serverSet = failover;
      }
      boolean startTls = settings.startTls() && urls.stream().noneMatch(u -> "ldaps".equalsIgnoreCase(u.getScheme()));
      this.startTlsContext = startTls ? ssl.createSSLContext() : null;
    } catch (LDAPException | GeneralSecurityException e) {
      throw new IllegalArgumentException("Invalid LDAP connection settings for directory " + settings.id() + " (" + settings.urls() + "): "
          + e.getMessage(), e);
    }
  }

  public String id() {
    return settings.id();
  }

  /** The configured servers, in order. */
  public List<String> urls() {
    return settings.urls();
  }

  /** The server ("host:port") the most recent connection went to; null before the first one. */
  public String currentServer() {
    return lastServer;
  }

  /** The first configured server. */
  public String url() {
    return settings.urls().get(0);
  }

  // ---------------------------------------------------------------------------
  // Connections
  // ---------------------------------------------------------------------------

  private LDAPConnectionPool pool() {
    LDAPConnectionPool p = pool;
    if (p != null) return p;
    synchronized (this) {
      if (pool == null) {
        try {
          BindRequest bind = settings.bindDn() == null || settings.bindDn().isBlank()
              ? null
              : new SimpleBindRequest(settings.bindDn(), settings.bindPassword());
          PostConnectProcessor tls = startTlsContext == null ? null : new StartTLSPostConnectProcessor(startTlsContext);
          PostConnectProcessor track = new PostConnectProcessor() {
            @Override
            public void processPreAuthenticatedConnection(LDAPConnection connection) throws LDAPException {
              noteServer(connection);
              if (tls != null) tls.processPreAuthenticatedConnection(connection);
            }

            @Override
            public void processPostAuthenticatedConnection(LDAPConnection connection) throws LDAPException {
              if (tls != null) tls.processPostAuthenticatedConnection(connection);
            }
          };
          LDAPConnectionPool created = new LDAPConnectionPool(serverSet, bind, 1, Math.max(1, settings.poolSize()), track);
          created.setRetryFailedOperationsDueToInvalidConnections(true);
          created.setConnectionPoolName("portal-" + settings.id());
          pool = created;
        } catch (LDAPException e) {
          throw unavailable(e);
        }
      }
      return pool;
    }
  }

  private void noteServer(LDAPConnection connection) {
    if (connection.getConnectedAddress() != null) lastServer = connection.getConnectedAddress() + ":" + connection.getConnectedPort();
  }

  private <T> T timed(String operation, Supplier<T> call) {
    if (meters == null) return call.get();
    long start = System.nanoTime();
    String outcome = "failure";
    try {
      T result = call.get();
      outcome = "success";
      return result;
    } finally {
      Timer.builder("ens.dsp.ldap.operation").description("LDAP operations").tag("operation", operation).tag("outcome", outcome)
          .tag("directory", settings.id())
          .register(meters).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    }
  }

  private void timed(String operation, Runnable call) {
    timed(operation, () -> {
      call.run();
      return null;
    });
  }

  private static DirectoryException unavailable(LDAPException e) {
    if (e.getResultCode() == ResultCode.INVALID_CREDENTIALS) {
      return new DirectoryException(DirectoryException.Code.UNAVAILABLE, "The directory rejected the service account credentials");
    }
    return new DirectoryException(DirectoryException.Code.UNAVAILABLE, "Directory server unavailable: " + e.getMessage());
  }

  private static DirectoryException wrap(LDAPException e) {
    ResultCode rc = e.getResultCode();
    if (rc == ResultCode.CONNECT_ERROR || rc == ResultCode.SERVER_DOWN || rc == ResultCode.TIMEOUT) return unavailable(e);
    return DirectoryException.from(e);
  }

  // ---------------------------------------------------------------------------
  // Operations
  // ---------------------------------------------------------------------------

  /**
   * Search, returning at most {@code limit} entries. {@code truncated} is set when more entries
   * exist (or when the server's own size limit cut the results short).
   */
  public Result search(String base, SearchScope scope, Filter filter, int limit, List<String> attributes) {
    return timed("search", () -> {
      String[] attrs = attributes == null || attributes.isEmpty() ? new String[0] : attributes.toArray(String[]::new);
      SearchRequest request = new SearchRequest(base, scope, filter, attrs);
      request.setSizeLimit(limit + 1);
      request.setTimeLimitSeconds(Math.max(1, settings.timeoutMs() / 1000));
      List<SearchResultEntry> found;
      boolean truncated;
      try {
        SearchResult result = pool().search(request);
        found = result.getSearchEntries();
        truncated = found.size() > limit;
      } catch (LDAPSearchException e) {
        if (e.getResultCode() != ResultCode.SIZE_LIMIT_EXCEEDED) throw wrap(e);
        found = e.getSearchEntries();
        truncated = true;
      }
      List<DirectoryEntry> entries = new ArrayList<>();
      for (SearchResultEntry e : found) {
        if (entries.size() >= limit) break;
        entries.add(toEntry(e));
      }
      return new Result(entries, truncated);
    });
  }

  /**
   * Stream every matching entry to {@code consumer}, up to {@code max} entries, without holding
   * them all in memory. Uses the simple paged results control (RFC 2696) so server-side size
   * limits for a single response don't cut the scan short; servers that don't support the control
   * ignore it (it is sent as non-critical) and answer in one response.
   */
  public ScanResult scan(String base, SearchScope scope, Filter filter, List<String> attributes, int max, Consumer<DirectoryEntry> consumer) {
    return timed("scan", () -> {
      String[] attrs = attributes == null || attributes.isEmpty() ? new String[0] : attributes.toArray(String[]::new);
      int[] seen = {0};
      boolean[] over = {false};
      // The SDK may call the listener on its connection reader thread, so a consumer failure is kept
      // and rethrown on the calling thread once the search has finished.
      RuntimeException[] failure = {null};
      SearchResultListener listener = new SearchResultListener() {
        @Override
        public void searchEntryReturned(SearchResultEntry entry) {
          if (failure[0] != null) return;
          if (seen[0] >= max) {
            over[0] = true;
            return;
          }
          seen[0]++;
          try {
            consumer.accept(toEntry(entry));
          } catch (RuntimeException e) {
            failure[0] = e;
          }
        }

        @Override
        public void searchReferenceReturned(SearchResultReference reference) {}
      };
      LDAPConnection connection;
      try {
        connection = pool().getConnection();
      } catch (LDAPException e) {
        throw wrap(e);
      }
      boolean healthy = false;
      try {
        ASN1OctetString cookie = null;
        while (true) {
          SearchRequest request = new SearchRequest(listener, base, scope, filter, attrs);
          // One more than still wanted, so "exactly max" and "more than max" can be told apart.
          request.setSizeLimit(Math.max(1, max - seen[0] + 1));
          request.setTimeLimitSeconds(Math.max(1, settings.timeoutMs() / 1000) * 6);
          request.addControl(new SimplePagedResultsControl(SCAN_PAGE_SIZE, cookie, false));
          SearchResult result;
          try {
            result = connection.search(request);
          } catch (LDAPSearchException e) {
            if (e.getResultCode() != ResultCode.SIZE_LIMIT_EXCEEDED) throw e;
            over[0] = true;
            healthy = true;
            break;
          }
          SimplePagedResultsControl paged = SimplePagedResultsControl.get(result);
          if (failure[0] != null || over[0] || paged == null || !paged.moreResultsToReturn()) {
            healthy = true;
            break;
          }
          cookie = paged.getCookie();
        }
      } catch (LDAPException e) {
        throw wrap(e);
      } finally {
        if (healthy) pool().releaseConnection(connection);
        else pool().releaseDefunctConnection(connection);
      }
      if (failure[0] != null) throw failure[0];
      return new ScanResult(seen[0], over[0]);
    });
  }

  /** Base-scope read; null when the entry does not exist. */
  public DirectoryEntry get(String dn, List<String> attributes) {
    return timed("get", () -> {
      try {
        SearchResultEntry e = pool().getEntry(dn, attributes == null ? new String[0] : attributes.toArray(String[]::new));
        return e == null ? null : toEntry(e);
      } catch (LDAPException e) {
        if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) return null;
        throw wrap(e);
      }
    });
  }

  public void add(String dn, Map<String, List<String>> attributes) {
    timed("add", () -> {
      List<Attribute> attrs = new ArrayList<>();
      attributes.forEach((k, v) -> {
        if (!v.isEmpty()) attrs.add(new Attribute(k, v));
      });
      try {
        pool().add(new Entry(dn, attrs));
      } catch (LDAPException e) {
        throw wrap(e);
      }
    });
  }

  public void modify(String dn, List<Modification> changes) {
    timed("modify", () -> {
      if (changes.isEmpty()) return;
      try {
        pool().modify(dn, changes);
      } catch (LDAPException e) {
        throw wrap(e);
      }
    });
  }

  /**
   * Adds and deletes values with the permissive modify control (non-critical): servers that know it
   * (Active Directory, OpenLDAP, 389-DS, the embedded server) skip values already present or
   * already absent, so a change built from a partial read (Active Directory's ranged member
   * retrieval) or one racing another change never fails on them, and never replaces the attribute.
   */
  public void modifyValues(String dn, List<Modification> changes) {
    timed("modify", () -> {
      if (changes.isEmpty()) return;
      try {
        com.unboundid.ldap.sdk.ModifyRequest request = new com.unboundid.ldap.sdk.ModifyRequest(dn, changes);
        request.addControl(new com.unboundid.ldap.sdk.controls.PermissiveModifyRequestControl(false));
        pool().modify(request);
      } catch (LDAPException e) {
        throw wrap(e);
      }
    });
  }

  /** Change the entry's RDN (keeping its parent). Returns the new DN. */
  public String rename(String dn, RDN newRdn) {
    return timed("rename", () -> {
      try {
        pool().modifyDN(dn, newRdn.toString(), true);
        DN parent = Dns.parse(dn).getParent();
        return parent == null || parent.isNullDN() ? newRdn.toString() : new DN(newRdn, parent).toString();
      } catch (LDAPException e) {
        throw wrap(e);
      }
    });
  }

  public void delete(String dn) {
    timed("delete", () -> {
      try {
        pool().delete(dn);
      } catch (LDAPException e) {
        throw wrap(e);
      }
    });
  }

  /** Verify a password with a simple bind on a dedicated connection. */
  public boolean authenticate(String dn, String password) {
    return timed("bind", () -> {
      // Never allow empty passwords: servers treat them as unauthenticated (anonymous) binds.
      if (dn == null || dn.isBlank() || password == null || password.isEmpty()) return false;
      try (LDAPConnection connection = serverSet.getConnection()) {
        noteServer(connection);
        if (startTlsContext != null) {
          ExtendedResult r = connection.processExtendedOperation(new StartTLSExtendedRequest(startTlsContext));
          if (r.getResultCode() != ResultCode.SUCCESS) throw new LDAPException(r);
        }
        connection.bind(dn, password);
        return true;
      } catch (LDAPException e) {
        if (e.getResultCode() == ResultCode.INVALID_CREDENTIALS || e.getResultCode() == ResultCode.NO_SUCH_OBJECT) return false;
        throw wrap(e);
      }
    });
  }

  /** Read the subschema subentry advertised by the root DSE. */
  public SchemaSnapshot readSchema() {
    return timed("schema", () -> {
      try {
        LDAPConnectionPool p = pool();
        String subschemaDn = p.getRootDSE() == null ? null : p.getRootDSE().getAttributeValue("subschemaSubentry");
        Schema schema = p.getSchema();
        if (schema == null) throw new DirectoryException(DirectoryException.Code.OTHER, "The directory does not publish a schema");
        return SchemaMapper.toSnapshot(schema, subschemaDn != null ? subschemaDn : schema.getSchemaEntry().getDN());
      } catch (LDAPException e) {
        throw wrap(e);
      }
    });
  }

  public void ping() {
    timed("ping", () -> {
      try {
        pool().getRootDSE();
      } catch (LDAPException e) {
        throw wrap(e);
      }
    });
  }

  /** Attributes whose values are binary (returned base64-encoded). */
  public static boolean isBinary(String attribute) {
    String a = attribute.toLowerCase();
    int semi = a.indexOf(';');
    return BINARY_ATTRIBUTES.contains(a) || (semi > 0 && BINARY_ATTRIBUTES.contains(a.substring(0, semi)));
  }

  static DirectoryEntry toEntry(Entry entry) {
    Map<String, List<String>> attributes = new LinkedHashMap<>();
    for (Attribute a : entry.getAttributes()) {
      String name = a.getName().toLowerCase();
      List<String> values = attributes.computeIfAbsent(name, k -> new ArrayList<>());
      if (BINARY_ATTRIBUTES.contains(name) || BINARY_ATTRIBUTES.contains(a.getBaseName().toLowerCase())) {
        for (byte[] b : a.getValueByteArrays()) values.add(Base64.getEncoder().encodeToString(b));
      } else {
        values.addAll(List.of(a.getValues()));
      }
    }
    return new DirectoryEntry(entry.getDN(), attributes);
  }

  @Override
  public void close() {
    if (pool != null) {
      pool.close();
      log.debug("Closed the LDAP connection pool of directory {}", settings.id());
    }
  }
}
