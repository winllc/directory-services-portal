package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Entries that end on a date (contractors, guests): the form's {@code date} field holding the
 * end date, and its {@code dn} field naming the sponsor. Each day the sponsor of each entry
 * ending within {@code warnDays} is mailed and the {@code entry.expiring} webhook event is sent;
 * the sponsor may move the date out, at most {@code maxExtensionDays} from today, without write
 * access to the definition.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EntryExpiry(
    @Size(max = 64) @Pattern(regexp = Patterns.ATTRIBUTE) String dateAttribute,
    @Size(max = 64) @Pattern(regexp = Patterns.ATTRIBUTE) String sponsorAttribute,
    @Min(1) @Max(365) Integer warnDays,
    @Min(1) @Max(3650) Integer maxExtensionDays) {

  public int warnDaysOrDefault() {
    return warnDays == null ? 14 : warnDays;
  }

  public int maxExtensionDaysOrDefault() {
    return maxExtensionDays == null ? 180 : maxExtensionDays;
  }

  /** As stored: none when neither field is named. */
  public EntryExpiry cleaned() {
    String date = dateAttribute == null || dateAttribute.isBlank() ? null : dateAttribute.trim();
    String sponsor = sponsorAttribute == null || sponsorAttribute.isBlank() ? null : sponsorAttribute.trim();
    if (date == null && sponsor == null) return null;
    return new EntryExpiry(date, sponsor, warnDays, maxExtensionDays);
  }
}
