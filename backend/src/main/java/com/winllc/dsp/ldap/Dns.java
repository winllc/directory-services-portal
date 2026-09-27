package com.winllc.dsp.ldap;

import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.RDN;

/** Distinguished-name helpers built on the UnboundID parser (RFC 4514 aware, case-insensitive). */
public final class Dns {
  private Dns() {}

  public static DN parse(String dn) {
    try {
      return new DN(dn);
    } catch (LDAPException e) {
      throw new DirectoryException(DirectoryException.Code.INVALID_DN, "Invalid DN: " + dn);
    }
  }

  public static boolean isValid(String dn) {
    if (dn == null || dn.isBlank()) return false;
    try {
      return !new DN(dn).isNullDN();
    } catch (LDAPException e) {
      return false;
    }
  }

  public static boolean equal(String a, String b) {
    if (a == null || b == null) return false;
    try {
      return new DN(a).equals(new DN(b));
    } catch (LDAPException e) {
      return false;
    }
  }

  /** True when {@code dn} is {@code base} or below it; with {@code strict}, strictly below. */
  public static boolean isUnder(String dn, String base, boolean strict) {
    try {
      return new DN(dn).isDescendantOf(new DN(base), !strict);
    } catch (LDAPException e) {
      return false;
    }
  }

  /** Number of RDNs between {@code dn} and {@code base} (0 = same entry, -1 = not below). */
  public static int depthBelow(String dn, String base) {
    try {
      DN d = new DN(dn);
      DN b = new DN(base);
      if (!d.isDescendantOf(b, true)) return -1;
      return d.getRDNs().length - b.getRDNs().length;
    } catch (LDAPException e) {
      return -1;
    }
  }

  /** Build {@code attr=value,parent} with correct escaping of the value. */
  public static String build(String rdnAttribute, String rdnValue, String parent) {
    RDN rdn = new RDN(rdnAttribute, rdnValue);
    if (parent == null || parent.isBlank()) return rdn.toString();
    return new DN(rdn, parse(parent)).toString();
  }

  /** First RDN value, handy for display ("cn=Jane Doe,ou=people" -> "Jane Doe"). */
  public static String rdnValue(String dn) {
    try {
      DN parsed = new DN(dn);
      return parsed.isNullDN() ? dn : parsed.getRDN().getAttributeValues()[0];
    } catch (LDAPException e) {
      return dn;
    }
  }
}
