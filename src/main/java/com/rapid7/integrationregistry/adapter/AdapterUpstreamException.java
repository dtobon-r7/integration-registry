package com.rapid7.integrationregistry.adapter;

public class AdapterUpstreamException extends Exception {

    public AdapterUpstreamException(String message) {
        super(message);
    }

    public AdapterUpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
