package com.rapid7.integrationregistry.adapter;

import com.rapid7.integrationregistry.adapter.exception.AdapterAuthException;
import com.rapid7.integrationregistry.adapter.exception.AdapterTimeoutException;
import com.rapid7.integrationregistry.adapter.exception.AdapterUpstreamException;
import org.springframework.http.HttpHeaders;

/**
 * Per-product seam for fetching integration metadata. Implementations live in the {@code adapter}
 * layer and are dispatched in parallel by the future fan-out coordinator.
 */
public interface IntegrationAdapter {

  /**
   * Identifies the Rapid7 product this adapter fetches from. Returns one of the values in the
   * canonical {@code productName()} set (RFC-001 §Canonical {@code productName()} values): {@code
   * "InsightIDR"}, {@code "InsightConnect"}, {@code "Surface Command"}, {@code "InsightVM"}, {@code
   * "InsightCloudSec"}, {@code "InsightAppSec"}.
   *
   * <p>Every {@link NormalizedIntegration} returned by {@link #fetch} on this adapter MUST carry
   * the same value in {@code NormalizedIntegration.productName} — the future aggregator keys
   * vendor-mapping resolution by {@code (productName, sourceType, sourceValue)}.
   */
  String productName();

  /**
   * Fetch this product's integrations for the given organization.
   *
   * <p>{@code authHeaders} is provided to the adapter for read-only use; adapters MUST NOT mutate
   * the passed instance. The fan-out coordinator may dispatch the same headers to multiple adapters
   * in parallel — mutation by one adapter would corrupt headers seen by others.
   */
  FetchResult fetch(String orgId, HttpHeaders authHeaders)
      throws AdapterTimeoutException, AdapterAuthException, AdapterUpstreamException;
}
