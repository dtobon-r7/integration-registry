package com.rapid7.integrationregistry.testsupport.examples;

import com.rapid7.integrationregistry.testsupport.FixtureLoader;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SampleContractTest {

    @Test
    void restClient_shouldReturnParsedBody_whenUpstreamRespondsWithFixture() {
        // Arrange
        String upstreamUrl = "https://upstream.example/api/health";
        String fixture = FixtureLoader.read("sample/healthy-response.json");
        // Order matters: bindTo mutates the builder's request factory in place,
        // so build the client AFTER bindTo to inherit the mock interceptor.
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        server.expect(requestTo(upstreamUrl))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withSuccess(fixture, MediaType.APPLICATION_JSON));

        // Act
        SampleResponse parsed = client.get()
            .uri(upstreamUrl)
            .retrieve()
            .body(SampleResponse.class);

        // Assert
        assertThat(parsed.status()).isEqualTo("healthy");
        assertThat(parsed.checked_at()).isEqualTo("2026-05-20T10:00:00Z");
        server.verify();
    }

    record SampleResponse(String status, String checked_at) {}
}
