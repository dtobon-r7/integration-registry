package com.rapid7.integrationregistry.adapter.insightconnect;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Plugin sub-object of {@link ConnectionViewModel}. {@code name} is the source identifier used for
 * vendor mapping ({@code source_type = "plugin_name"}); {@code pluginVendor} is the plugin author
 * ("rapid7"), NOT the third-party vendor.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Plugin(String name, String pluginVendor, String pluginVersion) {}
