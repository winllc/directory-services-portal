package com.winllc.dsp.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogSafeTest {
  @Test
  void escapesLineBreaksAndControlCharactersAndCutsLongValues() {
    assertThat(LogSafe.of("alice\r\n2026-01-01 INFO forged")).isEqualTo("alice\\r\\n2026-01-01 INFO forged");
    assertThat(LogSafe.of("a\u0000b\u2028c")).isEqualTo("a\\u0000b\\u2028c");
    assertThat(LogSafe.of(null)).isEqualTo("null");
    assertThat(LogSafe.of("x".repeat(1000))).hasSize(LogSafe.MAX + 3).endsWith("...");
  }
}
