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
    String json =
        "{\"v\":999,\"payload\":{\"integrations\":[],\"fetchedAt\":\"2026-06-01T12:00:00Z\"}}";

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

  @Test
  void encode_shouldWriteInstantsAsIso8601_notEpochTimestamps() {
    // Arrange — a known instant with a recognizable ISO-8601 form
    Instant fetchedAt = Instant.parse("2026-06-01T12:00:00Z");
    FetchResult result = CacheFetchResultFixtures.iconResult(fetchedAt);

    // Act
    String json = codec.encode(result);

    // Assert — the JSON must contain the ISO-8601 timestamp, NOT the epoch-seconds number
    assertThat(json).contains("2026-06-01T12:00:00Z");
    assertThat(json).doesNotContain("1748779200"); // epoch seconds for the above instant
  }

  @Test
  void decode_shouldTolerateUnknownFields_forForwardCompatibility() {
    // Arrange — inject an unknown field into a valid envelope (simulates decoding an envelope
    // written by a future service replica with an additive NormalizedIntegration field)
    String jsonWithUnknownField =
        """
        {
          "v": 1,
          "payload": {
            "integrations": [
              {
                "integrationId": "conn-1",
                "sourceIdentifier": {"sourceType": "plugin_name", "sourceValue": "jira"},
                "productName": "InsightConnect",
                "integrationType": "Automation Plugin",
                "integrationLabel": null,
                "status": "HEALTHY",
                "lastSuccessTimestamp": null,
                "configurationUrl": "https://icon.example/connections/conn-1",
                "customerAccountId": "org-123",
                "unknownFutureField": "this-field-does-not-exist-yet"
              }
            ],
            "fetchedAt": "2026-06-01T12:00:00Z"
          }
        }
        """;

    // Act
    Optional<FetchResult> decoded = codec.decode(jsonWithUnknownField);

    // Assert — the unknown field should be ignored; decode must still return a present result
    assertThat(decoded).isPresent();
    assertThat(decoded.get().integrations()).hasSize(1);
    assertThat(decoded.get().integrations().getFirst().integrationId()).isEqualTo("conn-1");
  }
}
