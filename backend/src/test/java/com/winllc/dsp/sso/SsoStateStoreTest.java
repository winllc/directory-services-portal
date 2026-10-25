package com.winllc.dsp.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The in-memory sign-in request store is bounded: anonymous starts can't grow it without limit. */
class SsoStateStoreTest {
  @Test
  void keepsAtMostTheConfiguredNumberOfRequestsInFlight() {
    SsoStateStore store = new SsoStateStore(null, 3);
    for (String h : new String[] {"a", "b", "c"}) store.put(h, h, Duration.ofMinutes(10));
    assertThat(store.acceptsMore()).isFalse();
    // Full of live requests: a new one is refused rather than displacing a sign-on in progress.
    assertThatThrownBy(() -> store.put("d", "d", Duration.ofMinutes(10))).isInstanceOf(SsoStateStore.FullException.class);
    assertThat(store.pendingInMemory()).isEqualTo(3);
    assertThat(store.peek("a")).isEqualTo("a");

    // Taking one makes room; expired ones are dropped when room is needed.
    assertThat(store.take("a")).isEqualTo("a");
    store.put("e", "e", Duration.ofMillis(-1));
    assertThat(store.acceptsMore()).isTrue();
    store.put("f", "f", Duration.ofMinutes(10));
    assertThat(store.pendingInMemory()).isEqualTo(3);
    assertThat(store.peek("e")).isNull();
    assertThat(store.peek("f")).isEqualTo("f");
  }

  @Test
  void theSweepDropsExpiredRequests() {
    SsoStateStore store = new SsoStateStore(null, 100);
    store.put("old", "x", Duration.ofMillis(-1));
    store.put("new", "y", Duration.ofMinutes(10));
    store.sweep();
    assertThat(store.pendingInMemory()).isEqualTo(1);
  }
}
