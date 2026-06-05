package com.rapid7.integrationregistry.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OutboundAuthTest {

  @Test
  void empty_hasNoHeaders() {
    assertThat(OutboundAuth.empty().headers()).isEmpty();
  }

  @Test
  void of_copiesAndExposesHeaders() {
    OutboundAuth auth = OutboundAuth.of(Map.of("X-IPIMS-ORG-ID", "org-1"));
    assertThat(auth.headers()).containsEntry("X-IPIMS-ORG-ID", "org-1");
  }

  @Test
  void of_isDefensivelyCopied() {
    Map<String, String> source = new HashMap<>();
    source.put("X-IPIMS-ORG-ID", "org-1");
    OutboundAuth auth = OutboundAuth.of(source);
    source.put("X-IPIMS-ORG-ID", "tampered");
    assertThat(auth.headers()).containsEntry("X-IPIMS-ORG-ID", "org-1");
  }

  @Test
  void headers_areUnmodifiable() {
    OutboundAuth auth = OutboundAuth.of(Map.of("a", "b"));
    assertThatThrownBy(() -> auth.headers().put("c", "d"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void of_rejectsNull() {
    assertThatThrownBy(() -> OutboundAuth.of(null)).isInstanceOf(NullPointerException.class);
  }
}
