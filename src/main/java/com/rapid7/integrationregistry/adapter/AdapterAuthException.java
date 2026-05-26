package com.rapid7.integrationregistry.adapter;

public class AdapterAuthException extends Exception {

    public AdapterAuthException(String message) {
        super(message);
    }

    public AdapterAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
