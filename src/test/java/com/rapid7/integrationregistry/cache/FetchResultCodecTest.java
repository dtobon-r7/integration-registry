package com.rapid7.integrationregistry.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.rapid7.integrationregistry.adapter.FetchResult;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FetchResultCodecTest {

  private final FetchResultCodec codec = new FetchResultCodec();

  @Test
  void decode_shouldReturnEquivalentResult_whenRoundTrippingEncodedValue() {
    // Arrange
    Instant fetchedAt = Instant.parse("2026-06-01T12:00:00Z");
    FetchResult original = CacheFetchResultFixtures.iconResult(fetchedAt);

    // Act
    String json = codec.encode(original);
    Optional<FetchResult> decoded = codec.decode(json);

    // Assert
    assertThat(decoded).contains(original);
  }

  @Test
  void encode_shouldEmbedSchemaVersion_whenEncoding() {
    // Act
    String json = codec.encode(CacheFetchResultFixtures.iconResult(Instant.EPOCH));

    // Assert
    assertThat(json).contains("\"v\":1");
  }

  @Test
  void decode_shouldReturnEmpty_whenVersionIsUnknown() {
    // Arrange — a future/unknown envelope version
    String json = "{\"v\":999,\"payload\":{\"integrations\":[],\"fetchedAt\":\"2026-06-01T12:00:00Z\"}}";

    // Act
    Optional<FetchResult> decoded = codec.decode(json);

    // Assert
    assertThat(decoded).isEmpty();
  }

  @Test
  void decode_shouldReturnEmpty_whenPayloadIsMalformed() {
    // Act
    Optional<FetchResult> decoded = codec.decode("not json at all");

    // Assert — never throws; a corrupt value is just a miss
    assertThat(decoded).isEmpty();
  }
}
