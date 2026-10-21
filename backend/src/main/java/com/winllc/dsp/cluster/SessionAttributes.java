package com.winllc.dsp.cluster;

import com.winllc.dsp.util.LogSafe;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;

/**
 * Reads session attributes back from {@code spring_session_attributes} (Java serialization) with an
 * allow-list of the classes the portal stores in sessions: the security context with its
 * authentication, authorities and {@code SessionUser}, and the boxed values and collections they
 * use (and arrays of Object, which the immutable lists are written as). Anything else (a gadget chain written by someone with access to the table) is refused
 * before it is instantiated, and the attribute reads as absent: at worst the user is signed out,
 * never a server error.
 */
public final class SessionAttributes implements Converter<byte[], Object> {
  private static final Logger log = LoggerFactory.getLogger(SessionAttributes.class);

  /** What the portal puts in a session (see SessionService, AuthController, ConsentService). */
  static final ObjectInputFilter ALLOWED = ObjectInputFilter.Config.createFilter(String.join(";",
      "maxdepth=16", "maxrefs=2000", "maxbytes=262144", "maxarray=10000",
      "!java.rmi.**", "!java.net.URL", "!java.util.PriorityQueue",
      "java.lang.String", "java.lang.Boolean", "java.lang.Long", "java.lang.Integer", "java.lang.Number",
      "java.util.ArrayList", "java.util.Collections$Unmodifiable*", "java.util.Collections$EmptyList", "java.util.ImmutableCollections$*",
      "java.util.CollSer", "java.lang.Object",
      "org.springframework.security.core.context.SecurityContextImpl",
      "org.springframework.security.authentication.AbstractAuthenticationToken",
      "org.springframework.security.authentication.UsernamePasswordAuthenticationToken",
      "org.springframework.security.core.authority.SimpleGrantedAuthority",
      "com.winllc.dsp.**",
      "!*"));

  private final ClassLoader loader;

  public SessionAttributes(ClassLoader loader) {
    this.loader = loader;
  }

  @Override
  public Object convert(byte[] source) {
    return read(source, loader);
  }

  /** The attribute, or null when it is unreadable or holds a class that isn't allowed. */
  public static Object read(byte[] source, ClassLoader loader) {
    if (source == null) return null;
    try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(source)) {
      @Override
      protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
        try {
          return Class.forName(desc.getName(), false, loader);
        } catch (ClassNotFoundException e) {
          return super.resolveClass(desc);
        }
      }
    }) {
      String[] refused = {null};
      in.setObjectInputFilter(info -> {
        ObjectInputFilter.Status status = ALLOWED.checkInput(info);
        if (status == ObjectInputFilter.Status.REJECTED && refused[0] == null) {
          refused[0] = info.serialClass() != null ? info.serialClass().getName() : "a limit (depth, references, size)";
        }
        return status;
      });
      try {
        return in.readObject();
      } catch (IOException | ClassNotFoundException | RuntimeException e) {
        log.warn("Ignored a session attribute that could not be read safely ({}): {}", LogSafe.of(refused[0]), LogSafe.of(e.getMessage()));
        return null;
      }
    } catch (IOException | RuntimeException e) {
      log.warn("Ignored a session attribute that could not be read: {}", LogSafe.of(e.getMessage()));
      return null;
    }
  }
}
