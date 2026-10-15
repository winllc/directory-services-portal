package com.winllc.dsp.license;

import java.time.LocalDate;
import java.util.Set;

/**
 * A license file whose signature checked out. {@code expires} is null for a perpetual license;
 * a license is good through the end of its expiry date (UTC).
 */
public record License(
        String licenseId,
        String product,
        String licensee,
        LocalDate issued,
        LocalDate expires,
        Set<String> features) {

    public boolean coversAllFeatures() {
        return features.contains("*");
    }

    public boolean covers(String feature) {
        return coversAllFeatures() || features.contains(feature);
    }

    public boolean isExpired(LocalDate today) {
        return expires != null && today.isAfter(expires);
    }
}
