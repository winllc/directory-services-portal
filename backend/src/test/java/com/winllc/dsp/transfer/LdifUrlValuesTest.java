package com.winllc.dsp.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.winllc.dsp.model.FormDefinition;
import com.winllc.dsp.model.FormField;
import com.winllc.dsp.web.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** LDIF values given as a URL ("attr:< file:///...") are never fetched: the file is refused first. */
class LdifUrlValuesTest {
  @TempDir Path dir;

  private static FormDefinition form() {
    FormField cn = new FormField("f-cn", "cn", "Name", null, "text", false, true, false, false, null, null, null, null, null, null, null, null);
    FormField d = new FormField("f-d", "description", "Description", null, "textarea", false, false, false, false, null, null, null, null, null, null,
        null, null);
    return new FormDefinition("form", "Thing", null, List.of("top", "device"), "cn", List.of(cn, d), null, null);
  }

  @Test
  void refusesUrlValuesPlainFoldedAndHttp() throws Exception {
    Path secret = dir.resolve("secret.txt");
    Files.writeString(secret, "TOP-SECRET-CONTENT");
    String url = secret.toUri().toString();
    for (String ldif : List.of(
        "dn: cn=a,ou=x,dc=example,dc=com\ncn: a\ndescription:< " + url + "\n",
        "dn: cn=a,ou=x,dc=example,dc=com\r\ncn: a\r\ndescription:< " + url + "\r\n",
        // Folded between the colon and the "<", and inside the attribute name.
        "dn: cn=a,ou=x,dc=example,dc=com\ncn: a\ndescription:\n < " + url + "\n",
        "dn: cn=a,ou=x,dc=example,dc=com\ncn: a\ndescri\n ption:< " + url + "\n",
        "dn: cn=a,ou=x,dc=example,dc=com\ncn: a\ndescription;lang-en:< " + url + "\n",
        "dn: cn=a,ou=x,dc=example,dc=com\ncn: a\ndescription:< http://169.254.169.254/latest/meta-data/\n",
        "dn: cn=a,ou=x,dc=example,dc=com\ncn:< file:///etc/passwd\n",
        "dn:< file:///etc/hostname\ncn: a\n",
        "dn: cn=a,ou=x,dc=example,dc=com\nchangetype: add\ncn: a\ndescription:< " + url + "\n",
        // A control's value, and ":<" later on the line.
        "dn: cn=a,ou=x,dc=example,dc=com\ncontrol: 1.2.3 true:< " + url + "\nchangetype: add\ncn: a\n",
        "dn: cn=a,ou=x,dc=example,dc=com\ncn: a\ndescription: x :< " + url + "\n",
        "dn: cn=a,ou=x,dc=example,dc=com\ncontrol: 1.2.3 true:\n < " + url + "\nchangetype: add\ncn: a\n")) {
      assertThatThrownBy(() -> EntryTransfer.parseLdif(form(), ldif, 100)).as(ldif)
          .isInstanceOf(ApiException.class).hasMessageContaining("URL");
    }
  }

  @Test
  void refusesTabLedLinesAndNeverNamesThePath() throws Exception {
    Path secret = dir.resolve("secret.txt");
    Files.writeString(secret, "TOP-SECRET-CONTENT");
    String url = secret.toUri().toString();
    assertThatThrownBy(() -> EntryTransfer.parseLdif(form(), "dn: cn=a,ou=x,dc=example,dc=com\ncn: a\ndescription:\n\t< " + url + "\n", 100))
        .isInstanceOf(ApiException.class).hasMessageContaining("tab").hasMessageNotContaining(secret.toString());
    for (String missing : List.of(url, "file:///nonexistent/dsp-" + System.nanoTime())) {
      assertThatThrownBy(() -> EntryTransfer.parseLdif(form(), "dn: cn=a,ou=x,dc=example,dc=com\ncn: a\ndescription:< " + missing + "\n", 100))
          .hasMessage("LDIF line 3: values given as a URL (\":<\") are not accepted; give the value itself, base64-encoded with \"::\" if it "
              + "contains \":<\"");
    }
  }

  @Test
  void acceptsCommentsAndBase64ValuesWithTheCharacters() {
    String ldif = """
        # a comment:< file:///etc/passwd is just text
        dn: cn=a,ou=x,dc=example,dc=com
        cn: a
        description:: c2VlIDo8IGZpbGU6Ly8vbm90aGluZw==
        """;
    assertThatCode(() -> EntryTransfer.parseLdif(form(), ldif, 100)).doesNotThrowAnyException();
    var rows = EntryTransfer.parseLdif(form(), ldif, 100);
    assertThat(rows.get(0).values().get("description")).isEqualTo("see :< file:///nothing");
  }
}
