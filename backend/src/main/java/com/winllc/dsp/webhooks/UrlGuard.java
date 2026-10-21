package com.winllc.dsp.webhooks;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * Keeps webhooks from being turned against the portal's own network (SSRF). A webhook URL must be
 * {@code https://host[:port]/path} without credentials, and every address its host resolves to
 * must be public: loopback, link-local, private (RFC 1918, unique-local IPv6), carrier-grade NAT,
 * multicast, documentation and other reserved ranges are refused, as are IPv6 forms that embed
 * such an IPv4 address. An administrator may allow-list host names (WEBHOOK_ALLOWED_HOSTS) whose
 * webhooks go to an internal service; cloud metadata endpoints (169.254.169.254, fd00:ec2::254,
 * 169.254.170.2, 100.100.100.200, 192.0.0.192) are refused even then.
 *
 * <p>The check runs when a webhook is saved and again, on a fresh resolution, at every delivery;
 * the delivery then connects to exactly the address that was checked, so a DNS answer that changes
 * in between (DNS rebinding) can't redirect it.
 */
public final class UrlGuard {
  /** Name resolution (replaceable in tests). */
  @FunctionalInterface
  public interface Resolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  /** Where to deliver: the checked URL, and the one address to connect to. */
  public record Target(URI uri, String host, int port, InetAddress address) {}

  /** A URL or address the guard refuses; the message says why. */
  public static final class Refused extends Exception {
    public Refused(String message) {
      super(message);
    }
  }

  private final List<String> allowedHosts;
  private final Resolver resolver;

  public UrlGuard(List<String> allowedHosts, Resolver resolver) {
    this.allowedHosts = allowedHosts == null ? List.of() : allowedHosts;
    this.resolver = resolver;
  }

  /** Checks the form of a webhook URL (no resolution). */
  public static URI parse(String url) throws Refused {
    if (url == null || url.isBlank()) throw new Refused("Enter the webhook's URL");
    if (url.length() > 2048) throw new Refused("The URL is too long");
    URI uri;
    try {
      uri = new URI(url.trim());
    } catch (URISyntaxException e) {
      throw new Refused("Not a valid URL");
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())) throw new Refused("Webhooks must use https://");
    if (uri.getRawUserInfo() != null) throw new Refused("The URL must not contain a user name or password");
    if (uri.getHost() == null || uri.getHost().isBlank()) throw new Refused("The URL has no host name");
    if (uri.getRawFragment() != null) throw new Refused("The URL must not have a fragment (#...)");
    if (uri.getPort() == 0 || uri.getPort() > 65535) throw new Refused("Invalid port");
    return uri;
  }

  /** True when the host name is allow-listed (exact, or {@code *.example.com} for its subdomains). */
  public boolean allowListed(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    for (String a : allowedHosts) {
      if (a.startsWith("*.") ? h.endsWith(a.substring(1)) && h.length() > a.length() - 1 : h.equals(a)) return true;
    }
    return false;
  }

  /**
   * Resolves the URL's host and checks every address; returns the address to connect to.
   *
   * @throws Refused when the host can't be resolved or any address is not allowed
   */
  public Target resolve(URI uri) throws Refused {
    return resolve(uri, null);
  }

  private static final java.util.concurrent.ExecutorService LOOKUPS = java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
    Thread t = new Thread(r, "dsp-webhook-dns");
    t.setDaemon(true);
    return t;
  });

  /** As {@link #resolve(URI)}, giving up on a name lookup that takes longer than {@code timeout} (null: no limit). */
  public Target resolve(URI uri, java.time.Duration timeout) throws Refused {
    String host = hostOf(uri);
    InetAddress[] addresses;
    try {
      if (timeout == null) {
        addresses = resolver.resolve(host);
      } else {
        java.util.concurrent.Future<InetAddress[]> lookup = LOOKUPS.submit(() -> resolver.resolve(host));
        try {
          addresses = lookup.get(Math.max(1, timeout.toMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
          lookup.cancel(true);
          throw new Refused("The host name " + host + " did not resolve within " + Math.max(1, timeout.toSeconds()) + " s");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new Refused("Interrupted while resolving " + host);
        } catch (java.util.concurrent.ExecutionException e) {
          if (e.getCause() instanceof UnknownHostException u) throw u;
          throw new Refused("The host name " + host + " cannot be resolved");
        }
      }
    } catch (UnknownHostException e) {
      throw new Refused("The host name " + host + " cannot be resolved");
    }
    if (addresses == null || addresses.length == 0) throw new Refused("The host name " + host + " cannot be resolved");
    boolean allowed = allowListed(host);
    for (InetAddress a : addresses) {
      String why = classify(a);
      if (why == null) continue;
      if (why.equals(METADATA)) throw new Refused(host + " resolves to a cloud metadata address, which webhooks may never reach");
      if (!allowed) {
        throw new Refused(host + " resolves to a " + why + " address (" + a.getHostAddress() + "); an administrator can allow it with "
            + "WEBHOOK_ALLOWED_HOSTS");
      }
    }
    int port = uri.getPort() == -1 ? 443 : uri.getPort();
    return new Target(uri, host, port, addresses[0]);
  }

  /** The host without IPv6 brackets. */
  static String hostOf(URI uri) {
    String host = uri.getHost();
    if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
    return host;
  }

  static final String METADATA = "cloud metadata";

  /** Why an address is not public (null when it is). */
  static String classify(InetAddress address) {
    byte[] b = address.getAddress();
    if (address instanceof Inet4Address) return classify4(b);
    if (address instanceof Inet6Address) return classify6(b);
    return "unknown";
  }

  private static int u(byte b) {
    return b & 0xff;
  }

  private static String classify4(byte[] b) {
    int a0 = u(b[0]), a1 = u(b[1]), a2 = u(b[2]), a3 = u(b[3]);
    if ((a0 == 169 && a1 == 254 && a2 == 169 && a3 == 254) || (a0 == 169 && a1 == 254 && a2 == 170 && a3 == 2)
        || (a0 == 100 && a1 == 100 && a2 == 100 && a3 == 200) || (a0 == 192 && a1 == 0 && a2 == 0 && a3 == 192)) {
      return METADATA;
    }
    if (a0 == 0) return "unspecified";
    if (a0 == 127) return "loopback";
    if (a0 == 169 && a1 == 254) return "link-local";
    if (a0 == 10 || (a0 == 172 && a1 >= 16 && a1 <= 31) || (a0 == 192 && a1 == 168)) return "private";
    if (a0 == 100 && a1 >= 64 && a1 <= 127) return "carrier-grade NAT";
    if (a0 >= 224) return a0 < 240 ? "multicast" : "reserved";
    if ((a0 == 192 && a1 == 0 && (a2 == 0 || a2 == 2)) || (a0 == 198 && a1 == 51 && a2 == 100) || (a0 == 203 && a1 == 0 && a2 == 113)
        || (a0 == 198 && (a1 == 18 || a1 == 19)) || (a0 == 192 && a1 == 88 && a2 == 99)) {
      return "reserved";
    }
    return null;
  }

  private static String classify6(byte[] b) {
    // fd00:ec2::254, the AWS metadata service over IPv6.
    if (u(b[0]) == 0xfd && u(b[1]) == 0x00 && u(b[2]) == 0x0e && u(b[3]) == 0xc2 && allZero(b, 4, 14) && u(b[14]) == 0x02 && u(b[15]) == 0x54) {
      return METADATA;
    }
    if (allZero(b, 0, 16)) return "unspecified";
    if (allZero(b, 0, 15) && u(b[15]) == 1) return "loopback";
    // Embedded IPv4: ::a.b.c.d (compatible), ::ffff:a.b.c.d (mapped), ::ffff:0:a.b.c.d (translated,
    // RFC 2765), 64:ff9b::a.b.c.d (NAT64 well-known prefix).
    boolean nat64 = u(b[0]) == 0x00 && u(b[1]) == 0x64 && u(b[2]) == 0xff && u(b[3]) == 0x9b;
    if (allZero(b, 0, 12) || (allZero(b, 0, 10) && u(b[10]) == 0xff && u(b[11]) == 0xff)
        || (allZero(b, 0, 8) && u(b[8]) == 0xff && u(b[9]) == 0xff && allZero(b, 10, 12)) || (nat64 && allZero(b, 4, 12))) {
      return classify4(new byte[] {b[12], b[13], b[14], b[15]});
    }
    // The rest of 64:ff9b::/32, including the local-use NAT64 prefix 64:ff9b:1::/48 (RFC 8215), whose
    // IPv4 address can sit at several places depending on the prefix length: refused outright.
    if (nat64) return "reserved";
    // 6to4 (2002::/16) carries an IPv4 address in bytes 2-5.
    if (u(b[0]) == 0x20 && u(b[1]) == 0x02) {
      String why = classify4(new byte[] {b[2], b[3], b[4], b[5]});
      if (why != null) return why;
    }
    if (u(b[0]) == 0xfe && (u(b[1]) & 0xc0) == 0x80) return "link-local";
    if (u(b[0]) == 0xfe && (u(b[1]) & 0xc0) == 0xc0) return "private";
    if ((u(b[0]) & 0xfe) == 0xfc) return "private";
    if (u(b[0]) == 0xff) return "multicast";
    // Documentation (2001:db8::/32) and Teredo (2001::/32), which can tunnel to any IPv4 address.
    if (u(b[0]) == 0x20 && u(b[1]) == 0x01 && ((u(b[2]) == 0x0d && u(b[3]) == 0xb8) || (u(b[2]) == 0 && u(b[3]) == 0))) return "reserved";
    return null;
  }

  private static boolean allZero(byte[] b, int from, int to) {
    for (int i = from; i < to; i++) if (b[i] != 0) return false;
    return true;
  }
}
