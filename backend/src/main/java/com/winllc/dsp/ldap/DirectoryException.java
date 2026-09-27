package com.winllc.dsp.ldap;

import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import org.springframework.http.HttpStatus;

/** A directory failure mapped to a portal error code and HTTP status. */
public class DirectoryException extends RuntimeException {

  public enum Code {
    NO_SUCH_OBJECT(HttpStatus.NOT_FOUND),
    ALREADY_EXISTS(HttpStatus.CONFLICT),
    INSUFFICIENT_ACCESS(HttpStatus.FORBIDDEN),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    SCHEMA_VIOLATION(HttpStatus.BAD_REQUEST),
    NOT_ALLOWED_ON_NON_LEAF(HttpStatus.CONFLICT),
    INVALID_DN(HttpStatus.BAD_REQUEST),
    INVALID_FILTER(HttpStatus.BAD_REQUEST),
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    OTHER(HttpStatus.BAD_GATEWAY);

    final HttpStatus status;

    Code(HttpStatus status) {
      this.status = status;
    }
  }

  private final Code code;

  public DirectoryException(Code code, String message) {
    super(message);
    this.code = code;
  }

  public Code code() {
    return code;
  }

  public HttpStatus status() {
    return code.status;
  }

  /** Translate an LDAP result (RFC 4511 result codes) into a portal error. */
  public static DirectoryException from(LDAPException e) {
    ResultCode rc = e.getResultCode();
    String diag = e.getDiagnosticMessage() != null ? e.getDiagnosticMessage() : e.getMessage();
    switch (rc.intValue()) {
      case 32:
        return new DirectoryException(Code.NO_SUCH_OBJECT, "The entry does not exist");
      case 68:
        return new DirectoryException(Code.ALREADY_EXISTS, "An entry with that name already exists");
      case 50:
        return new DirectoryException(Code.INSUFFICIENT_ACCESS, "The directory refused the operation (insufficient access)");
      case 49:
        return new DirectoryException(Code.INVALID_CREDENTIALS, "Invalid credentials");
      case 16, 17, 18, 19, 20, 21, 64, 65, 67, 69:
        return new DirectoryException(Code.SCHEMA_VIOLATION, diag == null || diag.isBlank() ? "The directory rejected the entry (schema violation)" : diag);
      case 66:
        return new DirectoryException(Code.NOT_ALLOWED_ON_NON_LEAF, "The entry has children and cannot be removed or renamed");
      case 34:
        return new DirectoryException(Code.INVALID_DN, "Invalid distinguished name");
      case 87:
        return new DirectoryException(Code.INVALID_FILTER, "Invalid search filter");
      case 51, 52, 80, 81, 85, 91:
        return new DirectoryException(Code.UNAVAILABLE, "Directory server unavailable: " + e.getMessage());
      default:
        return new DirectoryException(Code.OTHER, diag == null || diag.isBlank() ? "Directory error (" + rc + ")" : diag);
    }
  }
}
