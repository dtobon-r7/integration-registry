package com.rapid7.integrationregistry.cache;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rapid7.integrationregistry.adapter.FetchResult;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serializes a {@link FetchResult} to a versioned JSON envelope for Valkey storage and back.
 *
 * <p>The envelope is {@code {"v":1,"payload":{...}}}. On read, an unknown version or any
 * deserialization failure yields {@link Optional#empty()} — a cache miss, never an escaping
 * exception. This is what makes a future {@code NormalizedIntegration} schema change safe: old or
 * incompatible entries are silently ignored rather than breaking the read path.
 */
class FetchResultCodec {

  private static final Logger log = LoggerFactory.getLogger(FetchResultCodec.class);
  private static final int CURRENT_VERSION = 1;
  // Jackson 3 bundles java.time support in databind (JSR-310 was merged in), so no module
  // registration is needed, and WRITE_DATES_AS_TIMESTAMPS is disabled by default — Instants
  // already serialize as ISO-8601 strings. Only the unknown-property tolerance (forward-compat
  // for cache schema changes) needs an explicit override.
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  /** JSON envelope wrapping a payload with its schema version. */
  private record Envelope(@JsonProperty("v") int v, @JsonProperty("payload") FetchResult payload) {
    @JsonCreator
    Envelope {}
  }

  String encode(FetchResult result) {
    try {
      return MAPPER.writeValueAsString(new Envelope(CURRENT_VERSION, result));
    } catch (JacksonException e) {
      // FetchResult is a plain record of serializable fields; this should not happen.
      throw new IllegalStateException("Failed to encode FetchResult for cache", e);
    }
  }

  Optional<FetchResult> decode(String json) {
    if (json == null) {
      return Optional.empty();
    }
    try {
      Envelope envelope = MAPPER.readValue(json, Envelope.class);
      if (envelope.v() != CURRENT_VERSION || envelope.payload() == null) {
        log.debug("Cache payload version {} not readable; treating as miss", envelope.v());
        return Optional.empty();
      }
      return Optional.of(envelope.payload());
    } catch (JacksonException e) {
      log.debug("Cache payload unreadable; treating as miss", e);
      return Optional.empty();
    }
  }
}
