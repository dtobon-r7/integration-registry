package com.rapid7.integrationregistry.mapping;

import com.networknt.schema.ValidationMessage;
import com.rapid7.integrationregistry.adapter.exception.AdapterAuthException;
import com.rapid7.integrationregistry.adapter.exception.AdapterTimeoutException;
import com.rapid7.integrationregistry.adapter.exception.AdapterUpstreamException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BundleParseExceptionTest {

    @Test
    void yamlSyntaxError_shouldCarryCauseAndEmptyValidationMessages_whenInvoked() {
        // Arrange
        IOException cause = new IOException("yaml syntax error");

        // Act
        BundleParseException ex = BundleParseException.yamlSyntaxError(cause);

        // Assert
        assertThat(ex).isInstanceOf(Exception.class);
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getMessage()).contains("yaml syntax error");
        assertThat(ex.validationMessages()).isEmpty();
        assertThat(ex.validationMessages()).isUnmodifiable();
    }

    @Test
    void schemaInvalid_shouldCarryValidationMessagesAndNullCause_whenInvoked() {
        // Arrange
        Set<ValidationMessage> messages = Set.of();

        // Act
        BundleParseException ex = BundleParseException.schemaInvalid(messages);

        // Assert
        assertThat(ex).isInstanceOf(Exception.class);
        assertThat(ex.getCause()).isNull();
        assertThat(ex.getMessage()).contains("Bundle failed JSON Schema validation");
        assertThat(ex.validationMessages()).isEmpty();
    }

    @Test
    void schemaInvalid_shouldCopyMessagesDefensively_whenSourceSetMutatedAfterConstruction() {
        // Arrange
        java.util.Set<ValidationMessage> mutable = new java.util.HashSet<>();

        // Act
        BundleParseException ex = BundleParseException.schemaInvalid(mutable);

        // Assert
        assertThat(ex.validationMessages()).isNotSameAs(mutable);
    }

    @Test
    void validationMessages_shouldBeUnmodifiable_whenAccessed() {
        // Arrange
        BundleParseException ex = BundleParseException.schemaInvalid(Collections.emptySet());

        // Act / Assert
        assertThat(ex.validationMessages()).isUnmodifiable();
    }

    @Test
    void independentlyCatchable_shouldNotShareParentWithAdapterExceptions_whenThrown() {
        // Arrange
        // If a future refactor introduces a shared parent above Exception
        // (e.g., a "RegistryException" abstract class) for either family,
        // these isNotInstanceOf assertions will fail. The two exception
        // families (mapping.BundleParseException and adapter.exception.*)
        // are deliberately independent — see ADR-0001.

        // Act / Assert
        BundleParseException caught = BundleParseException.yamlSyntaxError(new java.io.IOException("test"));
        assertThat(caught)
            .isNotInstanceOf(AdapterAuthException.class)
            .isNotInstanceOf(AdapterTimeoutException.class)
            .isNotInstanceOf(AdapterUpstreamException.class);
    }
}
