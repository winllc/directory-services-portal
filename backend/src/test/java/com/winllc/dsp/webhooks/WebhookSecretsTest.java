package com.winllc.dsp.webhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.winllc.dsp.cluster.Cluster;
import com.winllc.dsp.secrets.SecretBox;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Webhook signing secrets at rest: encrypted with DSP_SECRETS_KEY, older plaintext ones kept working and encrypted. */
class WebhookSecretsTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  @TempDir Path dir;

  private static byte[] key(int fill) {
    byte[] k = new byte[32];
    Arrays.fill(k, (byte) fill);
    return k;
  }

  private Path file() {
    return dir.resolve(Webhooks.FILE);
  }

  private Webhooks open(SecretBox box) {
    return new Webhooks(file(), MAPPER, Cluster.standalone(), box);
  }

  private String stored() throws Exception {
    return Files.readString(file(), StandardCharsets.UTF_8);
  }

  @Test
  void theBoxSealsPerRecordAndOpensOnlyWithTheSameKeyAndRecord() {
    SecretBox box = SecretBox.of(key(1));
    String sealed = box.seal("whsec_abc", "webhook:1");
    assertThat(sealed).startsWith(SecretBox.PREFIX + box.keyId() + ":").doesNotContain("whsec_abc");
    assertThat(box.seal("whsec_abc", "webhook:1")).as("a new data key and nonce each time").isNotEqualTo(sealed);
    assertThat(box.open(sealed, "webhook:1")).isEqualTo("whsec_abc");
    // A value from before encryption reads as it is.
    assertThat(box.open("whsec_plain", "webhook:1")).isEqualTo("whsec_plain");
    // Copied onto another record: refused.
    assertThatThrownBy(() -> box.open(sealed, "webhook:2")).isInstanceOf(SecretBox.UnreadableSecretException.class)
        .hasMessageContaining("does not decrypt");
    // Another key, or none: refused, naming the key it needs.
    SecretBox other = SecretBox.of(key(2));
    assertThatThrownBy(() -> other.open(sealed, "webhook:1")).isInstanceOf(SecretBox.UnreadableSecretException.class)
        .hasMessageContaining("another key").hasMessageContaining(box.keyId()).hasMessageContaining(other.keyId());
    assertThatThrownBy(() -> SecretBox.none().open(sealed, "webhook:1")).isInstanceOf(SecretBox.UnreadableSecretException.class)
        .hasMessageContaining("DSP_SECRETS_KEY is not set");
    // Damaged.
    String damaged = sealed.substring(0, sealed.length() - 4) + (sealed.endsWith("AAAA") ? "BBBB" : "AAAA");
    assertThatThrownBy(() -> box.open(damaged, "webhook:1")).isInstanceOf(SecretBox.UnreadableSecretException.class);
    // Without a key nothing is sealed.
    assertThat(SecretBox.none().seal("whsec_abc", "webhook:1")).isEqualTo("whsec_abc");
  }

  @Test
  void theKeyMustBe32BytesOfBase64() {
    com.winllc.dsp.secrets.SecretsProperties p = new com.winllc.dsp.secrets.SecretsProperties();
    p.setKey(Base64.getEncoder().encodeToString(key(3)));
    assertThat(new SecretBox(p).enabled()).isTrue();
    p.setKey(Base64.getUrlEncoder().withoutPadding().encodeToString(key(3)));
    assertThat(new SecretBox(p).keyId()).isEqualTo(SecretBox.of(key(3)).keyId());
    p.setKey(Base64.getEncoder().encodeToString(new byte[16]));
    assertThatThrownBy(() -> new SecretBox(p)).isInstanceOf(IllegalStateException.class).hasMessageContaining("16 bytes");
    p.setKey("not base64 !");
    assertThatThrownBy(() -> new SecretBox(p)).isInstanceOf(IllegalStateException.class).hasMessageContaining("not base64");
    p.setKey("");
    assertThat(new SecretBox(p).enabled()).isFalse();
  }

  @Test
  void withAKeySecretsAreStoredEncryptedAndStillSign() throws Exception {
    Webhooks hooks = open(SecretBox.of(key(1)));
    Webhooks.Created c = hooks.create("crm", "https://hooks.example/a", List.of("entry.changed"), List.of(), true, "admin");
    assertThat(stored()).doesNotContain(c.secret()).contains(SecretBox.PREFIX);
    Webhooks.Webhook w = hooks.get(c.webhook().id());
    assertThat(hooks.signingSecret(w)).isEqualTo(c.secret());

    hooks.update(w.id(), "crm 2", "https://hooks.example/b", List.of("entry.changed"), List.of(), true);
    assertThat(hooks.signingSecret(hooks.get(w.id()))).isEqualTo(c.secret());
    Webhooks.Created rotated = hooks.rotate(w.id());
    assertThat(stored()).doesNotContain(rotated.secret()).doesNotContain(c.secret());
    assertThat(hooks.signingSecret(hooks.get(w.id()))).isEqualTo(rotated.secret());

    // The next start (same key) reads them.
    assertThat(open(SecretBox.of(key(1))).signingSecret(hooks.get(w.id()))).isEqualTo(rotated.secret());
  }

  @Test
  void withoutAKeySecretsAreStoredAsBefore() throws Exception {
    Webhooks hooks = open(SecretBox.none());
    Webhooks.Created c = hooks.create("crm", "https://hooks.example/a", List.of("entry.changed"), List.of(), true, "admin");
    assertThat(stored()).contains(c.secret());
    assertThat(hooks.signingSecret(hooks.get(c.webhook().id()))).isEqualTo(c.secret());
  }

  @Test
  void aMixedStoreIsReadAndItsPlaintextSecretsAreEncryptedAtStart() throws Exception {
    // A store written before the key (plaintext), then one webhook added with the key.
    Webhooks before = open(SecretBox.none());
    Webhooks.Created plain = before.create("old", "https://hooks.example/old", List.of("entry.changed"), List.of(), true, "admin");
    SecretBox box = SecretBox.of(key(1));
    String sealedSecret = "whsec_" + "x".repeat(43);
    List<Webhooks.Webhook> mixed = new java.util.ArrayList<>(before.all());
    mixed.add(new Webhooks.Webhook("22222222-2222-2222-2222-222222222222", "new", "https://hooks.example/new", box.seal(sealedSecret,
        "webhook:22222222-2222-2222-2222-222222222222"), List.of("entry.changed"), List.of(), true, "admin", "2026-10-01T00:00:00Z",
        "2026-10-01T00:00:00Z"));
    Files.writeString(file(), MAPPER.writeValueAsString(mixed));
    assertThat(stored()).contains(plain.secret());

    Webhooks after = open(box);
    // Encrypted at start: no plaintext secret is left in the store, and both still sign.
    assertThat(stored()).doesNotContain(plain.secret()).doesNotContain(sealedSecret);
    assertThat(after.all()).allMatch(w -> SecretBox.sealed(w.secret()));
    assertThat(after.signingSecret(after.get(plain.webhook().id()))).isEqualTo(plain.secret());
    assertThat(after.signingSecret(after.get("22222222-2222-2222-2222-222222222222"))).isEqualTo(sealedSecret);
  }

  @Test
  void aStoreEncryptedWithAnotherKeyIsRefusedAndLeftAlone() throws Exception {
    Webhooks first = open(SecretBox.of(key(1)));
    first.create("crm", "https://hooks.example/a", List.of("entry.changed"), List.of(), true, "admin");
    // And one stored unencrypted, which a wrong key must not encrypt either.
    List<Webhooks.Webhook> list = new java.util.ArrayList<>(first.all());
    list.add(new Webhooks.Webhook("33333333-3333-3333-3333-333333333333", "old", "https://hooks.example/old", "whsec_plaintext",
        List.of("entry.changed"), List.of(), true, "admin", "2026-10-01T00:00:00Z", "2026-10-01T00:00:00Z"));
    Files.writeString(file(), MAPPER.writeValueAsString(list));
    String before = stored();
    String needed = SecretBox.of(key(1)).keyId();

    assertThatThrownBy(() -> open(SecretBox.of(key(2)))).isInstanceOf(IllegalStateException.class).hasMessageContaining("Refusing to start")
        .hasMessageContaining(needed).hasMessageContaining(SecretBox.of(key(2)).keyId());
    assertThatThrownBy(() -> open(SecretBox.none())).isInstanceOf(IllegalStateException.class).hasMessageContaining("DSP_SECRETS_KEY is not set");
    assertThat(stored()).isEqualTo(before);

    // With the right key it starts, and encrypts the plaintext one.
    Webhooks right = open(SecretBox.of(key(1)));
    assertThat(right.signingSecret(right.get("33333333-3333-3333-3333-333333333333"))).isEqualTo("whsec_plaintext");
    assertThat(stored()).doesNotContain("whsec_plaintext");
  }
}
