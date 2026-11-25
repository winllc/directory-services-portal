package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.winllc.dsp.web.validation.LdapFilter;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/**
 * Self-service group access for a directory definition whose entries are groups: signed-in users
 * of the definition's LDAP directory can look its groups up and ask to join, and the groups' owners
 * (or anyone who may edit the definition) approve or reject. Off unless {@code enabled}.
 *
 * <ul>
 *   <li>{@code memberAttribute}: the form's member field (member, uniqueMember, memberUid or a
 *       multi-valued DN field); none: the first of member, uniqueMember, memberUid the form has.</li>
 *   <li>{@code ownerAttribute}: the DN-valued attribute naming who approves (a person, or a group
 *       whose members all approve); none: {@code owner}.</li>
 *   <li>{@code requestFilter}: only groups matching this LDAP filter take requests; the others can be
 *       looked up but not requested. None: every group.</li>
 *   <li>{@code requireReason}: a request must say why.</li>
 *   <li>{@code allowLeave}: members may remove themselves.</li>
 *   <li>{@code ownersManageOwners}: a group's owners may add and remove its owners (the owner
 *       attribute must then be a multi-valued field of the form).</li>
 * </ul>
 *
 * <p>Settings added after the first release are objects, not primitives: definitions stored (and
 * requests sent) without them must still be read, and mean what the portal did before.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GroupAccess(
    boolean enabled,
    @Size(max = 64) @Pattern(regexp = Patterns.ATTRIBUTE) String memberAttribute,
    @Size(max = 64) @Pattern(regexp = Patterns.ATTRIBUTE) String ownerAttribute,
    @Size(max = 1000) @LdapFilter String requestFilter,
    boolean requireReason,
    boolean allowLeave,
    Boolean ownersManageOwners) {

  public static final String DEFAULT_OWNER_ATTRIBUTE = "owner";

  public String ownerAttributeOrDefault() {
    return ownerAttribute == null || ownerAttribute.isBlank() ? DEFAULT_OWNER_ATTRIBUTE : ownerAttribute.toLowerCase(Locale.ROOT);
  }

  /** Whether a group's owners may add and remove its owners. */
  public boolean ownersManage() {
    return Boolean.TRUE.equals(ownersManageOwners);
  }

  /** As stored: blanks and settings that are off dropped. */
  public GroupAccess cleaned() {
    return new GroupAccess(enabled, blank(memberAttribute), blank(ownerAttribute), blank(requestFilter), requireReason, allowLeave,
        ownersManage() ? Boolean.TRUE : null);
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
