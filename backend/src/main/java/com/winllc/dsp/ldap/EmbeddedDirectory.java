package com.winllc.dsp.ldap;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryDirectoryServerSnapshot;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.schema.Schema;
import com.unboundid.ldif.LDIFException;
import com.unboundid.ldif.LDIFReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

/**
 * A real LDAP server (UnboundID in-memory directory) listening on a loopback port, seeded with
 * the standard schema, the non-standard ACME schema and a demo organisation. The portal talks
 * to it over LDAP exactly as it would to a production server.
 */
public class EmbeddedDirectory implements AutoCloseable {
  public static final String BASE_DN = "dc=example,dc=com";
  public static final String SERVICE_DN = "cn=portal,ou=system," + BASE_DN;
  public static final String SERVICE_PASSWORD = "portal-secret";
  public static final String LOGIN_HINT =
      "Demo directory: sign in as admin (administrator), alice, bob, erin (HR), dave (helpdesk) or frank (partner). Password: password";

  private static final Logger log = LoggerFactory.getLogger(EmbeddedDirectory.class);

  private final InMemoryDirectoryServer server;
  private final InMemoryDirectoryServerSnapshot initialState;

  public EmbeddedDirectory() {
    try {
      InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
      config.setSchema(Schema.mergeSchemas(Schema.getDefaultStandardSchema(), readSchema("demo/acme-schema.ldif")));
      config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", InetAddress.getLoopbackAddress(), 0, null));
      config.setEnforceAttributeSyntaxCompliance(true);
      server = new InMemoryDirectoryServer(config);
      try (InputStream in = new ClassPathResource("demo/demo-data.ldif").getInputStream()) {
        server.importFromLDIF(true, new LDIFReader(in));
      }
      server.startListening();
      initialState = server.createSnapshot();
      log.info("Embedded demo directory listening on {} (sign in as admin / password)", url());
    } catch (LDAPException | IOException e) {
      throw new IllegalStateException("Could not start the embedded demo directory", e);
    }
  }

  private static Schema readSchema(String resource) throws IOException, LDAPException {
    try (InputStream in = new ClassPathResource(resource).getInputStream()) {
      LDIFReader reader = new LDIFReader(in);
      try {
        return new Schema(reader.readEntry());
      } catch (LDIFException e) {
        throw new IOException("Invalid schema LDIF " + resource, e);
      }
    }
  }

  public String url() {
    return "ldap://127.0.0.1:" + server.getListenPort();
  }

  /** Restore the seeded data (used by tests). */
  public void reset() {
    server.restoreSnapshot(initialState);
  }

  @Override
  public void close() {
    server.shutDown(true);
  }
}
