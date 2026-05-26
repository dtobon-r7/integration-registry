package com.rapid7.integrationregistry.adapter;

public class AdapterTimeoutException extends Exception {

    public AdapterTimeoutException(String message) {
        super(message);
    }

    public AdapterTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
