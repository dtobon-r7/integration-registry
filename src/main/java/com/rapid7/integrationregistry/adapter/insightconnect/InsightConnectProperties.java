package com.rapid7.integrationregistry.adapter.insightconnect;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the InsightConnect adapter, bound from the {@code
 * integration-registry.insightconnect.*} property tree.
 *
 * <p>{@code baseUrl} (scheme+host of the connections API) and {@code iconBase} (base for {@code
 * configuration_url} deep-links) have no defaults — the deploy environment supplies them; absent
 * config fails fast at binding. {@code timeout} defaults to 5 seconds (a starting value; the T07
 * coordinator owns the fan-out deadline).
 *
 * <p>Activated via {@code @EnableConfigurationProperties} on {@link InsightConnectClientConfig}.
 */
@ConfigurationProperties("integration-registry.insightconnect")
public record InsightConnectProperties(String baseUrl, String iconBase, Duration timeout) {

  private static final String FIELD_BASE_URL = "baseUrl";
  private static final String FIELD_ICON_BASE = "iconBase";
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

  public InsightConnectProperties {
    // Required URLs: reject null AND blank/whitespace at binding, so misconfiguration
    // fails fast here rather than surfacing as an opaque runtime error on the first call.
    baseUrl = requireText(baseUrl, FIELD_BASE_URL);
    iconBase = requireText(iconBase, FIELD_ICON_BASE);
    // Strip a trailing slash so URL building never produces a double slash, regardless
    // of how the deploy environment formats the value — baseUrl feeds RestClient's URI
    // builder and iconBase feeds configuration_url templating.
    baseUrl = stripTrailingSlash(baseUrl);
    iconBase = stripTrailingSlash(iconBase);
    if (timeout == null) {
      timeout = DEFAULT_TIMEOUT;
    }
  }

  private static String requireText(String value, String field) {
    Objects.requireNonNull(value, field);
    String trimmed = value.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return trimmed;
  }

  private static String stripTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
