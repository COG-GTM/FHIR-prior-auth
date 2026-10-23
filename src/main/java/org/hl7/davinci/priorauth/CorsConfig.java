package org.hl7.davinci.priorauth;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Global CORS policy driven by the {@code priorauth.cors.allowed-origins}
 * allow-list (env {@code CORS_ALLOWED_ORIGINS}, comma separated). When the list
 * is empty no cross-origin access is granted (same-origin only). The same
 * allow-list is applied to the WebSocket endpoints in {@link WebSocketConfig}.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

  private final String[] allowedOrigins;

  public CorsConfig(@Value("${priorauth.cors.allowed-origins:}") String[] allowedOrigins) {
    this.allowedOrigins = Arrays.stream(allowedOrigins).map(String::trim).filter(origin -> !origin.isEmpty())
        .filter(origin -> !"*".equals(origin)).toArray(String[]::new);
  }

  public String[] getAllowedOrigins() {
    return allowedOrigins.clone();
  }

  public boolean isCorsEnabled() {
    return allowedOrigins.length > 0;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    if (!isCorsEnabled())
      return;
    registry.addMapping("/**").allowedOrigins(allowedOrigins)
        .allowedMethods("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS").allowedHeaders("*");
  }
}
