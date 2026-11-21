package com.winllc.dsp.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;

/** Collapsing: a bounded number of windows, and wrong pull-API tokens as failed sign-ins. */
class AccessDenialsTest {
  @TempDir Path dir;

  static MockHttpServletRequest request(String path) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setRemoteAddr("10.1.2.3");
    request.addHeader("Authorization", "Bearer ensat_not-a-real-token");
    return request;
  }

  List<JsonNode> events(AuditLog log) {
    List<JsonNode> out = new ArrayList<>();
    for (AuditStore.Stored s : log.store().readAfter(0, 1000)) out.add(AuditChainTest.MAPPER.readTree(s.line()));
    return out;
  }

  @Test
  void aFullMapCollapsesNewDenialsPerActorWhateverThePath() {
    AuditLog log = AuditChainTest.newLog(dir);
    AccessDenials denials = new AccessDenials(log);
    denials.maxWindows = 2;
    for (String path : List.of("/api/a", "/api/b", "/api/c", "/api/d", "/api/e")) denials.denied(request(path), "forbidden", "Access denied");

    assertThat(denials.openWindows()).isEqualTo(3);
    List<JsonNode> recorded = events(log);
    assertThat(recorded).extracting(e -> e.get("details").get("path").asString()).containsExactly("/api/a", "/api/b", "/api/c");
    assertThat(recorded.get(2).get("details").get("anyPath").asBoolean()).isTrue();

    denials.closeWindows();
    JsonNode repeats = events(log).getLast();
    assertThat(repeats.get("details").get("count").asInt()).isEqualTo(2);
    assertThat(repeats.get("details").get("anyPath").asBoolean()).isTrue();
  }

  @Test
  void aWrongPullApiTokenIsAFailedSignInCollapsedPerAddress() {
    AuditLog log = AuditChainTest.newLog(dir);
    AccessDenials denials = new AccessDenials(log);
    for (int i = 0; i < 3; i++) denials.tokenRefused(request("/api/audit/events"));

    List<JsonNode> recorded = events(log);
    assertThat(recorded).hasSize(1);
    JsonNode first = recorded.get(0);
    assertThat(first.get("action").asString()).isEqualTo("auth.login");
    assertThat(first.get("outcome").asString()).isEqualTo("failure");
    assertThat(first.get("details").get("method").asString()).isEqualTo("api-token");
    assertThat(first.toString()).doesNotContain("ensat_");

    denials.closeWindows();
    JsonNode repeats = events(log).getLast();
    assertThat(repeats.get("details").get("count").asInt()).isEqualTo(2);
    assertThat(repeats.get("source").get("ip").asString()).isEqualTo("10.1.2.3");
  }
}
