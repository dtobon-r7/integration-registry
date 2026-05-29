package com.rapid7.integrationregistry.controller;

import com.rapid7.integrationregistry.coordinator.CoordinatorMarker;

class ControllerWithCoordinatorDependency {

  @SuppressWarnings("unused")
  private CoordinatorMarker illegal;
}
