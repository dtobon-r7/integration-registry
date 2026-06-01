package com.rapid7.integrationregistry.cache;

import com.rapid7.integrationregistry.adapter.FetchResult;
import com.rapid7.integrationregistry.adapter.IntegrationStatus;
import com.rapid7.integrationregistry.adapter.NormalizedIntegration;
import com.rapid7.integrationregistry.adapter.SourceIdentifier;
import java.time.Instant;
import java.util.List;

/** Builds {@link FetchResult} fixtures for cache tests — real records, no mocks. */
final class CacheFetchResultFixtures {

  private CacheFetchResultFixtures() {}

  static FetchResult iconResult(Instant fetchedAt) {
    NormalizedIntegration integration =
        new NormalizedIntegration(
            "conn-1",
            new SourceIdentifier("plugin_name", "jira"),
            "InsightConnect",
            "Automation Plugin",
            null,
            IntegrationStatus.HEALTHY,
            null,
            "https://icon.example/connections/conn-1",
            "org-123");
    return new FetchResult(List.of(integration), fetchedAt);
  }
}
