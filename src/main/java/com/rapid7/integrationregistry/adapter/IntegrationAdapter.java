package com.rapid7.integrationregistry.adapter;

import org.springframework.http.HttpHeaders;

public interface IntegrationAdapter {

    String productName();

    FetchResult fetch(String orgId, HttpHeaders authHeaders)
        throws AdapterTimeoutException,
               AdapterAuthException,
               AdapterUpstreamException;
}
