package com.winllc.dsp.config;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the bundled React client from {@code classpath:/static}. Real files are served as-is;
 * other paths without a file extension (client-side routes such as /d/staff) get index.html.
 * API paths and missing files still 404.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry.addResourceHandler("/**")
        .addResourceLocations("classpath:/static/")
        .resourceChain(true)
        .addResolver(new SpaFallbackResolver());
  }

  static class SpaFallbackResolver extends PathResourceResolver {
    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException {
      if (!resourcePath.isEmpty()) {
        Resource requested = location.createRelative(resourcePath);
        if (requested.exists() && requested.isReadable() && !resourcePath.endsWith("/")) return requested;
        String last = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
        if (resourcePath.startsWith("api/") || last.contains(".")) return null;
      }
      Resource index = location.createRelative("index.html");
      return index.exists() && index.isReadable() ? index : null;
    }
  }
}
