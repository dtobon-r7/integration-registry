package com.rapid7.integrationregistry.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;

public final class FixtureLoader {

    private FixtureLoader() {}

    public static String read(String relativePath) {
        String classpathResource = "fixtures/" + relativePath;
        try {
            return new ClassPathResource(classpathResource).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read fixture: " + classpathResource, e);
        }
    }
}
