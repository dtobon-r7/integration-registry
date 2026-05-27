package com.rapid7.integrationregistry.mapping;

import java.util.Optional;

public enum SourceType {
    PLUGIN_NAME("plugin_name"),
    PRODUCT_TYPE("product_type"),
    PRODUCT_NAME("product_name"),
    INTEGRATION_ID("integration_id");

    private final String wireForm;

    SourceType(String wireForm) {
        this.wireForm = wireForm;
    }

    public String wireForm() {
        return wireForm;
    }

    public static Optional<SourceType> fromWireForm(String wireForm) {
        for (SourceType type : values()) {
            if (type.wireForm.equals(wireForm)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
