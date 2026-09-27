package com.winllc.dsp.web.validation;

import com.winllc.dsp.ldap.Dns;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A non-empty, syntactically valid distinguished name (RFC 4514). */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidDn.Validator.class)
public @interface ValidDn {
  String message() default "Invalid DN";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  class Validator implements ConstraintValidator<ValidDn, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
      return value != null && value.length() <= 1024 && Dns.isValid(value);
    }
  }
}
