package com.rapid7.integrationregistry.adapter.insightconnect;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration properties for the InsightConnect adapter, bound from the
 * {@code integration-registry.insightconnect.*} property tree.
 *
 * <p>{@code baseUrl} (scheme+host of the connections API) and {@code iconBase}
 * (base for {@code configuration_url} deep-links) have no defaults — the
 * deploy environment supplies them; absent config fails fast at binding.
 * {@code timeout} defaults to 5 seconds (a starting value; the T07 coordinator
 * owns the fan-out deadline).
 *
 * <p>Activated via {@code @EnableConfigurationProperties} on
 * {@link InsightConnectClientConfig}.
 */
@ConfigurationProperties("integration-registry.insightconnect")
public record InsightConnectProperties(
    String baseUrl,
    String iconBase,
    Duration timeout
) {

    private static final String FIELD_BASE_URL = "baseUrl";
    private static final String FIELD_ICON_BASE = "iconBase";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    public InsightConnectProperties {
        Objects.requireNonNull(baseUrl, FIELD_BASE_URL);
        Objects.requireNonNull(iconBase, FIELD_ICON_BASE);
        // Strip a trailing slash so configuration_url templating
        // ({iconBase}/automation/connections/{id}) never produces a double slash,
        // regardless of how the deploy environment formats the value.
        iconBase = stripTrailingSlash(iconBase);
        if (timeout == null) {
            timeout = DEFAULT_TIMEOUT;
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
