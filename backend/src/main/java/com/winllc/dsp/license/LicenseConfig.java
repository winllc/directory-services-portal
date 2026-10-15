package com.winllc.dsp.license;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The ENS Solutions license ({@code ens.license.file}, env ENS_LICENSE_FILE). Without a valid
 * license the portal runs its core features; the API paths of each {@link LicensedFeature} answer
 * 403 until a license enables it.
 */
@Configuration
public class LicenseConfig implements WebMvcConfigurer {
  public static final String PRODUCT = "directory-services-portal";

  @Value("${ens.license.file:license.lic}")
  private String file;

  @Bean
  public LicenseService licenseService() {
    return new LicenseService(LicenseVerifier.forProduct(PRODUCT), Path.of(file), PRODUCT, Clock.systemUTC());
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    LicenseService licenses = licenseService();
    for (LicensedFeature feature : LicensedFeature.values()) {
      registry.addInterceptor(new LicenseInterceptor(licenses, feature)).addPathPatterns(feature.paths());
    }
  }
}
