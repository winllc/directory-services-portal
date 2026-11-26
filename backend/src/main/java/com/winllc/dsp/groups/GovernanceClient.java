package com.winllc.dsp.groups;

import com.winllc.dsp.web.ApiException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Talks to the governance console in governed mode (see {@link GovernanceProperties}) with its
 * governance:delegate token: looks a group up as an entitlement, asks for access on a person's
 * behalf, and reads back where such a request stands.
 */
@Component
public class GovernanceClient {
  /** How long a lookup is reused: group searches look many groups up. */
  static final Duration LOOKUP_TTL = Duration.ofMinutes(5);
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  /** An entitlement as governance knows it: {@code managedBy} is grant, rule or unmanaged. */
  public record Lookup(String id, boolean known, boolean requestable, String managedBy) {}

  /** A request's id and state in the console. */
  public record Remote(String id, String state, String detail) {}

  private record Cached(Lookup lookup, Instant until) {}

  private final GovernanceProperties props;
  private final JsonMapper mapper;
  private final HttpClient http;
  private final Clock clock;
  private final Map<String, Cached> lookups = new ConcurrentHashMap<>();

  public GovernanceClient(GovernanceProperties props, JsonMapper mapper) {
    this.props = props;
    this.mapper = mapper;
    this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    this.clock = Clock.systemUTC();
  }

  public GovernanceProperties properties() {
    return props;
  }

  /** Forgets cached lookups (tests, a changed setting). */
  public void forget() {
    lookups.clear();
  }

  private JsonNode call(String method, String path, Object body) {
    String token = props.token();
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(props.base() + path)).timeout(TIMEOUT)
        .header("Authorization", "Bearer " + token).header("Accept", "application/json");
    if (body == null) {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
    }
    HttpResponse<String> res;
    try {
      res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "The governance console can't be reached: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(HttpStatus.BAD_GATEWAY, "The governance console can't be reached");
    }
    JsonNode json;
    try {
      json = res.body() == null || res.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(res.body());
    } catch (RuntimeException e) {
      json = mapper.createObjectNode();
    }
    int s = res.statusCode();
    if (s >= 200 && s < 300) return json;
    String message = json.has("message") ? json.get("message").asString() : "the governance console answered " + s;
    HttpStatus status = switch (s) {
      case 400 -> HttpStatus.BAD_REQUEST;
      case 403 -> HttpStatus.FORBIDDEN;
      case 404 -> HttpStatus.NOT_FOUND;
      case 409 -> HttpStatus.CONFLICT;
      default -> HttpStatus.BAD_GATEWAY;
    };
    throw new ApiException(status, "Governance: " + message);
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  /** The group {@code dn} of governance system {@code system}. */
  public Lookup lookup(String system, String dn) {
    String key = system + "|" + dn.toLowerCase(java.util.Locale.ROOT);
    Cached c = lookups.get(key);
    if (c != null && c.until().isAfter(clock.instant())) return c.lookup();
    JsonNode j = call("GET", "/api/v1/governance/entitlements/lookup?system=" + enc(system) + "&native_id=" + enc(dn), null);
    Lookup l = new Lookup(j.hasNonNull("id") ? j.get("id").asString() : null, j.path("known").asBoolean(false),
        j.path("requestable").asBoolean(false), j.path("managed_by").asString(""));
    lookups.put(key, new Cached(l, clock.instant().plus(LOOKUP_TTL)));
    return l;
  }

  /** Asks for {@code entitlement} on behalf of {@code login}, for themselves. */
  public Remote submit(String login, String entitlement, String reason) {
    return submit(login, entitlement, reason, null);
  }

  /**
   * As above, for access until the end of {@code until} (a day, UTC, as {@code yyyy-MM-dd}; null:
   * no end asked): the item ends at the start of the next day.
   */
  public Remote submit(String login, String entitlement, String reason, String until) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("on_behalf_of", login);
    body.put("source", "dsp");
    body.put("reason", reason);
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("entitlement_id", entitlement);
    if (until != null) item.put("ends_at", java.time.LocalDate.parse(until).plusDays(1) + "T00:00:00Z");
    body.put("items", List.of(item));
    JsonNode j = call("POST", "/api/v1/governance/requests", body);
    return new Remote(j.get("id").asString(), j.path("state").asString(""), "");
  }

  /** Where request {@code id} (made through this token) stands. */
  public Remote state(String id) {
    JsonNode j = call("GET", "/api/v1/governance/requests/" + enc(id) + "/state", null);
    return new Remote(id, j.path("state").asString(""), j.path("detail").asString(""));
  }
}
