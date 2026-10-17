package com.winllc.dsp.x509;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * Bounded HTTP fetches of OCSP responses and CRLs: http and https only (redirects too, at most
 * three), 5 s to connect and 10 s to read, a size limit, and never to link-local, multicast,
 * unspecified or cloud-metadata addresses (a certificate's AIA or CRL distribution point is
 * chosen by whoever issued it). Loopback and private addresses are allowed, e.g. a local OCSP
 * relay on an air-gapped network.
 */
class RevocationFetcher {
  static final int CONNECT_TIMEOUT_MS = 5_000;
  static final int READ_TIMEOUT_MS = 10_000;
  static final int MAX_REDIRECTS = 3;
  /** Cloud instance-metadata addresses outside the link-local range (Alibaba, AWS IPv6, Oracle). */
  private static final Set<String> METADATA = Set.of("100.100.100.200", "fd00:ec2:0:0:0:0:0:254", "192.0.0.192");

  /** GET (body null) or POST the body; the response body when the server answered 200. */
  byte[] fetch(URI uri, byte[] body, String contentType, long maxBytes) throws IOException {
    URI current = uri;
    for (int hop = 0; ; hop++) {
      checkTarget(current);
      HttpURLConnection c = (HttpURLConnection) current.toURL().openConnection();
      try {
        c.setInstanceFollowRedirects(false);
        c.setUseCaches(false);
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        if (body != null) {
          c.setRequestMethod("POST");
          c.setDoOutput(true);
          c.setRequestProperty("Content-Type", contentType);
          c.setFixedLengthStreamingMode(body.length);
          try (OutputStream out = c.getOutputStream()) {
            out.write(body);
          }
        }
        int status = c.getResponseCode();
        if (status >= 300 && status < 400 && c.getHeaderField("Location") != null) {
          if (hop >= MAX_REDIRECTS) throw new IOException("Too many redirects from " + uri);
          current = current.resolve(c.getHeaderField("Location"));
          continue;
        }
        if (status != 200) throw new IOException(current + " answered HTTP " + status);
        long length = c.getContentLengthLong();
        if (length > maxBytes) throw new IOException(current + " sent " + length + " bytes, more than the " + maxBytes + " allowed");
        try (InputStream in = c.getInputStream()) {
          return readAtMost(in, maxBytes, current);
        }
      } finally {
        c.disconnect();
      }
    }
  }

  private static byte[] readAtMost(InputStream in, long maxBytes, URI from) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    long total = 0;
    int n;
    while ((n = in.read(buf)) > 0) {
      total += n;
      if (total > maxBytes) throw new IOException(from + " sent more than the " + maxBytes + " bytes allowed");
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  /** Refuses schemes other than http(s) and hosts that resolve to a blocked address. */
  static void checkTarget(URI uri) throws IOException {
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https")) throw new IOException("Not fetched (only http and https): " + uri);
    String host = uri.getHost();
    if (host == null || host.isBlank()) throw new IOException("No host in " + uri);
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(host);
    } catch (UnknownHostException e) {
      throw new IOException("Unknown host " + host, e);
    }
    for (InetAddress a : addresses) {
      if (blocked(a)) throw new IOException("Not fetched: " + host + " is a link-local, multicast or metadata address (" + a.getHostAddress() + ")");
    }
  }

  static boolean blocked(InetAddress a) {
    return a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isMulticastAddress() || METADATA.contains(a.getHostAddress());
  }
}
