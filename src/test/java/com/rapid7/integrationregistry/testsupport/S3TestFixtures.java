package com.rapid7.integrationregistry.testsupport;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * Shared helpers for tests that stub the AWS SDK v2 {@code S3Client.getObject(...)}
 * call with synthetic byte responses.
 */
public final class S3TestFixtures {

    private S3TestFixtures() {}

    /**
     * Wrap a byte array as the {@link ResponseBytes} value the AWS SDK v2
     * {@code ResponseTransformer.toBytes()} would produce on a successful
     * {@code GetObject}. The {@link GetObjectResponse} envelope is empty.
     */
    public static ResponseBytes<GetObjectResponse> responseBytesOf(byte[] body) {
        return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), body);
    }
}
