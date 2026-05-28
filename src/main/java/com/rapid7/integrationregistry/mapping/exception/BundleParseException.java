package com.rapid7.integrationregistry.mapping.exception;

import com.networknt.schema.ValidationMessage;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Thrown by {@link com.rapid7.integrationregistry.mapping.BundleParser#parse}
 * when the input cannot be parsed as YAML or when the parsed document fails
 * JSON Schema validation. Caught by the bundle's boot-time loader, which maps
 * it to a readiness-probe-down state plus a structured log entry built from
 * {@link #validationMessages()}.
 *
 * <p>For YAML syntax failures, the underlying Jackson exception is the cause
 * and {@link #validationMessages()} is empty. For schema-validation failures,
 * the cause is null and {@link #validationMessages()} carries the structured
 * messages from the validator.
 *
 * <p>Payload-style exception per ADR-001: structured data on the failure
 * (the validation messages) is accessible via the dedicated accessor; the
 * human-readable summary is available via {@link #getMessage()}. See the
 * package Javadoc ({@link com.rapid7.integrationregistry.mapping.exception})
 * for the family's no-parent rule.
 */
public class BundleParseException extends Exception {

    private static final long serialVersionUID = 1L;

    private final Set<ValidationMessage> validationMessages;

    private BundleParseException(String message, Throwable cause, Set<ValidationMessage> messages) {
        super(message, cause);
        this.validationMessages = messages;
    }

    public static BundleParseException yamlSyntaxError(Throwable cause) {
        return new BundleParseException(
            "Bundle YAML could not be parsed: " + cause.getMessage(),
            cause,
            Set.of());
    }

    public static BundleParseException schemaInvalid(Set<ValidationMessage> messages) {
        Set<ValidationMessage> copy = Set.copyOf(messages);
        String summary = copy.stream()
            .map(m -> m.getInstanceLocation() + ": " + m.getMessage())
            .collect(Collectors.joining("; "));
        return new BundleParseException(
            "Bundle failed JSON Schema validation: " + summary,
            null,
            copy);
    }

    public Set<ValidationMessage> validationMessages() {
        return validationMessages;
    }
}
