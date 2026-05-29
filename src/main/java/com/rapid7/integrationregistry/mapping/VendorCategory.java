package com.rapid7.integrationregistry.mapping;

import java.util.Optional;

public enum VendorCategory {
  CLOUD_PROVIDER("cloud_provider"),
  IDENTITY("identity"),
  ITSM("itsm"),
  SIEM("siem"),
  EDR("edr"),
  NOTIFICATION("notification"),
  OTHER("other");

  private final String wireForm;

  VendorCategory(String wireForm) {
    this.wireForm = wireForm;
  }

  public String wireForm() {
    return wireForm;
  }

  public static Optional<VendorCategory> fromWireForm(String wireForm) {
    for (VendorCategory category : values()) {
      if (category.wireForm.equals(wireForm)) {
        return Optional.of(category);
      }
    }
    return Optional.empty();
  }
}
