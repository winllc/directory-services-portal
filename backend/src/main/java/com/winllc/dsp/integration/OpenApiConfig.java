package com.winllc.dsp.integration;

import com.winllc.dsp.sso.SsoProperties;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI 3 description of the integration API, served at {@value IntegrationApi#OPENAPI_PATH}
 * (JSON; {@code .yaml} for YAML) and generated in CI. Its server is the configured public address
 * (PORTAL_PUBLIC_URL), never one taken from the request's Host or Forwarded headers; without it,
 * the document names the server as "/" (the address it was fetched from).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {
  public static final String SECURITY_SCHEME = "apiToken";

  @Bean
  OpenAPI integrationOpenApi(SsoProperties sso) {
    String server = sso.getPublicUrl().isBlank() ? "/" : sso.getPublicUrl();
    return new OpenAPI()
        .info(new Info()
            .title("Directory Services Portal integration API")
            .version("1")
            .description("Directory search and read, entry changes and group membership for other systems. Authenticate with an API "
                + "token an administrator created (Administration > Integrations): Authorization: Bearer dspat_... Each call acts as the "
                + "token's creator, with their current permissions narrowed by the token's scopes and directories, and is audited.")
            .license(new License().name("LicenseRef-ENS-Commercial")))
        .servers(List.of(new Server().url(server)))
        .components(new Components().addSecuritySchemes(SECURITY_SCHEME, new SecurityScheme()
            .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("dspat_")
            .description("An API token. Scopes: " + String.join(", ", ApiScope.ids()))));
  }

  /** The error answers every operation can give, with the portal's error body. */
  @Bean
  OpenApiCustomizer integrationErrors() {
    Map<String, String> errors = Map.of(
        "400", "Invalid input (details names the fields)",
        "401", "No, unknown, expired or revoked API token (code token_required, token_unknown, token_expired, token_revoked)",
        "403", "The token lacks a scope (code insufficient_scope), its creator lacks the permission, or the license doesn't allow it",
        "404", "No such directory or entry, or one the token can't reach",
        "429", "Over the token's rate limit (code rate_limited); see Retry-After");
    return api -> {
      Schema<?> error = new ObjectSchema()
          .addProperty("error", new StringSchema().description("A message for people"))
          .addProperty("code", new StringSchema().description("A machine-readable reason, when there is one"))
          .addProperty("details", new ObjectSchema().additionalProperties(new StringSchema()).description("Per-field messages"));
      api.getComponents().addSchemas("Error", error);
      api.getPaths().values().forEach(path -> path.readOperations().forEach(op -> errors.forEach((status, text) ->
          op.getResponses().addApiResponse(status, new ApiResponse().description(text)
              .content(new Content().addMediaType("application/json", new MediaType().schema(new Schema<>().$ref("#/components/schemas/Error"))))))));
    };
  }
}
