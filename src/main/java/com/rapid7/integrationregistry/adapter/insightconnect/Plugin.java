package com.rapid7.integrationregistry.adapter.insightconnect;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Plugin sub-object of {@link ConnectionViewModel}. komand returns the human-readable plugin title
 * in {@code name} (e.g. "Jira") and the canonical plugin slug in {@code slugName} (e.g. {@code
 * rapid7_insightconnect_jira}). The slug is the stable identity used for vendor mapping ({@code
 * source_type = "plugin_name"}, per RFC-001: "ICON's plugin identity is its slug"); {@code
 * pluginVendor} is the plugin author ("rapid7"), NOT the third-party vendor.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Plugin(String name, String slugName, String pluginVendor, String pluginVersion) {}
