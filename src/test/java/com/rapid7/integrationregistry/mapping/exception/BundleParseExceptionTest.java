package com.rapid7.integrationregistry.mapping.exception;

import com.networknt.schema.ValidationMessage;
import com.rapid7.integrationregistry.adapter.exception.AdapterException;
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
    void independentlyCatchable_shouldNotBeAdapterException_whenThrown() {
        // Arrange
        // ADR-001 family-independence rule: the bundle family and the adapter
        // family are mutually independent. A bundle exception is not
        // assignable to AdapterException (the adapter family parent), so a
        // catch (AdapterException) clause never picks up a bundle exception.

        // Act / Assert
        BundleParseException caught = BundleParseException.yamlSyntaxError(new IOException("test"));
        assertThat(caught).isNotInstanceOf(AdapterException.class);
    }
}
