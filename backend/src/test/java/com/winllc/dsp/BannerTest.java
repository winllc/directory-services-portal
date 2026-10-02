package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.winllc.dsp.config.PortalProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class BannerTest extends ApiTestSupport {
  @Autowired PortalProperties props;

  @AfterEach
  void reset() {
    props.getBanner().setText("");
    props.getBanner().setForeground("#ffffff");
    props.getBanner().setBackground("#007a33");
  }

  @Test
  void acceptsCssColorsAndRejectsAnythingElse() {
    for (String ok : new String[] {"#fff", "#FFFA", "#007a33", "#007a33cc", "white", "DarkRed", "rgb(0, 122, 51)", "rgba(0,0,0,.5)", "hsl(120 100% 25%)"}) {
      assertThat(PortalProperties.Banner.isColor(ok)).as(ok).isTrue();
    }
    for (String bad : new String[] {"", "#12", "#12345", "red; position:fixed", "url(x)", "expression(alert(1))", "#fff}body{", "rgb(1,2,3);x", "var(--x)"}) {
      assertThat(PortalProperties.Banner.isColor(bad)).as(bad).isFalse();
    }
  }

  @Test
  void isOffByDefaultAndPublishedWhenConfigured() throws Exception {
    assertThat(body(anonymous(get("/api/info"))).has("banner")).isFalse();

    props.getBanner().setText("UNCLASSIFIED");
    props.getBanner().setForeground("white");
    props.getBanner().setBackground("#007a33");
    JsonNode banner = body(anonymous(get("/api/info"))).get("banner");
    assertThat(banner.get("text").asString()).isEqualTo("UNCLASSIFIED");
    assertThat(banner.get("foreground").asString()).isEqualTo("white");
    assertThat(banner.get("background").asString()).isEqualTo("#007a33");
  }

  @Test
  void neverPublishesInvalidColors() throws Exception {
    props.getBanner().setText("TEST");
    props.getBanner().setBackground("red; position:fixed");
    assertThat(props.getBanner().problems()).anyMatch(p -> p.contains("BANNER_BACKGROUND"));
    assertThat(body(anonymous(get("/api/info"))).has("banner")).isFalse();
  }
}
