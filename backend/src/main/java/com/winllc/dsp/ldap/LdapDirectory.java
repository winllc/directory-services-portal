package com.winllc.dsp.ldap;

import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.BindRequest;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.ExtendedResult;
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
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.ServerSet;
import com.unboundid.ldap.sdk.SimpleBindRequest;
import com.unboundid.ldap.sdk.SingleServerSet;
import com.unboundid.ldap.sdk.StartTLSPostConnectProcessor;
import com.unboundid.ldap.sdk.extensions.StartTLSExtendedRequest;
import com.unboundid.ldap.sdk.schema.Schema;
import com.unboundid.util.ssl.HostNameSSLSocketVerifier;
import com.unboundid.util.ssl.JVMDefaultTrustManager;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import com.winllc.dsp.model.DirectoryEntry;
import com.winllc.dsp.model.SchemaSnapshot;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gateway to the directory server. All reads and writes use a pooled connection bound as the
 * service account; the portal enforces its own per-definition permissions on top. User
 * passwords are verified with a separate, short-lived bind.
 */
public class LdapDirectory implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(LdapDirectory.class);
  private static final Set<String> BINARY_ATTRIBUTES =
      Set.of("jpegphoto", "thumbnailphoto", "usercertificate", "usercertificate;binary", "objectguid", "objectsid", "photo");

  public record Settings(
      String url, String bindDn, String bindPassword, boolean startTls, boolean tlsRejectUnauthorized, int timeoutMs, int poolSize) {}

  public record Result(List<DirectoryEntry> entries, boolean truncated) {}

  private final Settings settings;
  private final ServerSet serverSet;
  private final SSLContext startTlsContext;
  private volatile LDAPConnectionPool pool;

  public LdapDirectory(Settings settings) {
    this.settings = settings;
    try {
      LDAPURL url = new LDAPURL(settings.url());
      boolean ldaps = "ldaps".equalsIgnoreCase(url.getScheme());
      SSLUtil ssl = new SSLUtil(settings.tlsRejectUnauthorized() ? JVMDefaultTrustManager.getInstance() : new TrustAllTrustManager());

      LDAPConnectionOptions options = new LDAPConnectionOptions();
      options.setConnectTimeoutMillis(settings.timeoutMs());
      options.setResponseTimeoutMillis(settings.timeoutMs());
      if ((ldaps || settings.startTls()) && settings.tlsRejectUnauthorized()) {
        options.setSSLSocketVerifier(new HostNameSSLSocketVerifier(true));
      }
      SocketFactory socketFactory = ldaps ? ssl.createSSLSocketFactory() : SocketFactory.getDefault();
      this.serverSet = new SingleServerSet(url.getHost(), url.getPort(), socketFactory, options);
      this.startTlsContext = settings.startTls() && !ldaps ? ssl.createSSLContext() : null;
    } catch (LDAPException | GeneralSecurityException e) {
      throw new IllegalArgumentException("Invalid LDAP connection settings for " + settings.url() + ": " + e.getMessage(), e);
    }
  }

  public String url() {
    return settings.url();
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
          LDAPConnectionPool created = new LDAPConnectionPool(serverSet, bind, 1, Math.max(1, settings.poolSize()), tls);
          created.setRetryFailedOperationsDueToInvalidConnections(true);
          created.setConnectionPoolName("portal");
          pool = created;
        } catch (LDAPException e) {
          throw unavailable(e);
        }
      }
      return pool;
    }
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
  }

  /** Base-scope read; null when the entry does not exist. */
  public DirectoryEntry get(String dn, List<String> attributes) {
    try {
      SearchResultEntry e = pool().getEntry(dn, attributes == null ? new String[0] : attributes.toArray(String[]::new));
      return e == null ? null : toEntry(e);
    } catch (LDAPException e) {
      if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) return null;
      throw wrap(e);
    }
  }

  public void add(String dn, Map<String, List<String>> attributes) {
    List<Attribute> attrs = new ArrayList<>();
    attributes.forEach((k, v) -> {
      if (!v.isEmpty()) attrs.add(new Attribute(k, v));
    });
    try {
      pool().add(new Entry(dn, attrs));
    } catch (LDAPException e) {
      throw wrap(e);
    }
  }

  public void modify(String dn, List<Modification> changes) {
    if (changes.isEmpty()) return;
    try {
      pool().modify(dn, changes);
    } catch (LDAPException e) {
      throw wrap(e);
    }
  }

  /** Change the entry's RDN (keeping its parent). Returns the new DN. */
  public String rename(String dn, RDN newRdn) {
    try {
      pool().modifyDN(dn, newRdn.toString(), true);
      DN parent = Dns.parse(dn).getParent();
      return parent == null || parent.isNullDN() ? newRdn.toString() : new DN(newRdn, parent).toString();
    } catch (LDAPException e) {
      throw wrap(e);
    }
  }

  public void delete(String dn) {
    try {
      pool().delete(dn);
    } catch (LDAPException e) {
      throw wrap(e);
    }
  }

  /** Verify a password with a simple bind on a dedicated connection. */
  public boolean authenticate(String dn, String password) {
    // Never allow empty passwords: servers treat them as unauthenticated (anonymous) binds.
    if (dn == null || dn.isBlank() || password == null || password.isEmpty()) return false;
    try (LDAPConnection connection = serverSet.getConnection()) {
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
  }

  /** Read the subschema subentry advertised by the root DSE. */
  public SchemaSnapshot readSchema() {
    try {
      LDAPConnectionPool p = pool();
      String subschemaDn = p.getRootDSE() == null ? null : p.getRootDSE().getAttributeValue("subschemaSubentry");
      Schema schema = p.getSchema();
      if (schema == null) throw new DirectoryException(DirectoryException.Code.OTHER, "The directory does not publish a schema");
      return SchemaMapper.toSnapshot(schema, subschemaDn != null ? subschemaDn : schema.getSchemaEntry().getDN());
    } catch (LDAPException e) {
      throw wrap(e);
    }
  }

  public void ping() {
    try {
      pool().getRootDSE();
    } catch (LDAPException e) {
      throw wrap(e);
    }
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
      log.debug("Closed LDAP connection pool");
    }
  }
}
