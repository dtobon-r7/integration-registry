package com.rapid7.integrationregistry.adapter.insightconnect;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Top-level response from {@code GET /api/class3/v1/connections}. komand wraps the payload in a
 * {@code data} object carrying the connection list under {@code connections} and pagination under
 * {@code meta} (verified against live komand: {@code service/connectionsvc/http.go} {@code
 * getConnectionsWithTestsData}). The adapter reads {@code data.connections}; {@code
 * data.meta.total} is carried for the truncation tripwire.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectionsResponse(Data data) {

  /** The {@code data} envelope: the connection list plus pagination metadata. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Data(List<ConnectionViewModel> connections, Meta meta) {}

  /** Pagination metadata. Only {@code total} is consumed (the truncation tripwire). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Meta(Integer total) {}

  /** The connection list, or an empty list when {@code data} or {@code connections} is absent. */
  public List<ConnectionViewModel> connections() {
    if (data == null) {
      return List.of();
    }
    List<ConnectionViewModel> connections = data.connections();
    return connections == null ? List.of() : connections;
  }

  /** The reported total, or {@code null} when no {@code meta.total} is present. */
  public Integer total() {
    if (data == null) {
      return null;
    }
    Meta meta = data.meta();
    return meta == null ? null : meta.total();
  }
}
