package com.rapid7.integrationregistry.service;

import jakarta.servlet.http.HttpServletRequest;

class ServiceWithHttpDependency {

    @SuppressWarnings("unused")
    private HttpServletRequest illegal;
}
