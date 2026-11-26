package com.winllc.dsp.groups;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Requests are ordered by when they were closed, not by the text that is stored: an instant on a
 * whole second is written without a fraction and would sort after the rest of that second.
 */
class AccessRequestOrderTest {

  private static AccessRequests.Request closed(String id, String decidedAt) {
    return new AccessRequests.Request(id, "def-groups", "cn=engineering,ou=groups,dc=example,dc=com", "engineering", "default:dave",
        "uid=dave,ou=people,dc=example,dc=com", "Dave Diaz", "uid=dave,ou=people,dc=example,dc=com", null, AccessRequests.APPROVED,
        "2026-03-01T09:00:00Z", decidedAt, "default:carol", "Carol Chen", null, null, null, null, null, null);
  }

  @Test
  void aRequestClosedOnAWholeSecondIsNotTakenForTheLatestOfThatSecond() {
    AccessRequests.Request first = closed("req-1", "2026-03-01T10:00:00Z");
    AccessRequests.Request second = closed("req-2", "2026-03-01T10:00:00.250Z");
    AccessRequests.Request third = closed("req-3", "2026-03-01T10:00:00.900Z");

    // As text, the first would come last.
    assertThat(first.decidedAt()).isGreaterThan(third.decidedAt());
    List<String> newestFirst = Stream.of(second, first, third).sorted(Comparator.comparing(AccessRequests.Request::closedAt).reversed())
        .map(AccessRequests.Request::id).toList();
    assertThat(newestFirst).containsExactly("req-3", "req-2", "req-1");
  }

  @Test
  void anOpenRequestIsOrderedByWhenItWasMade() {
    AccessRequests.Request open = closed("req-4", null);
    assertThat(open.closedAt()).isEqualTo(java.time.Instant.parse("2026-03-01T09:00:00Z"));
  }
}
