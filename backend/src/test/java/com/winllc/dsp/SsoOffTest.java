package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;

/** Without SSO_* settings no single sign-on exists: no buttons, no endpoints. */
class SsoOffTest extends ApiTestSupport {
  @Test
  void nothingIsOfferedOrReachable() throws Exception {
    assertThat(body(anonymous(get("/api/info"))).get("auth").get("sso")).isEmpty();
    for (String url : new String[] {"/api/auth/sso/authorize/oidc", "/api/auth/sso/saml/authenticate/saml", "/api/auth/sso/saml/metadata",
        "/api/auth/sso/callback/oidc?code=x&state=y"}) {
      assertThat(status(anonymous(get(url)))).as(url).isEqualTo(404);
    }
    // The SAML endpoint is not exempt from the request header check when SAML is off... and serves nothing either way.
    assertThat(status(anonymous(post("/api/auth/sso/saml/acs/saml").param("SAMLResponse", "x")))).isIn(403, 404);
  }
}
