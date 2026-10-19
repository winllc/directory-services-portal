package com.winllc.dsp.sso;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.security.saml2.provider.service.web.Saml2AuthenticationRequestRepository;

/** Beans Spring Security looks up by type for SAML sign-in. */
@Configuration(proxyBeanMethods = false)
class SsoBeans {
  /** SAML AuthnRequests in flight: kept server-side, not in the (SameSite=Strict) session. */
  @Bean
  Saml2AuthenticationRequestRepository<AbstractSaml2AuthenticationRequest> samlAuthenticationRequestRepository(SsoRequests requests) {
    return requests.saml;
  }
}
