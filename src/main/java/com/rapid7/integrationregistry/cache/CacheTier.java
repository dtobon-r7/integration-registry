package com.rapid7.integrationregistry.cache;

/** The two independent cache tiers. {@code token} is the lowercase segment used in the Valkey key. */
enum CacheTier {
  FRESH("fresh"),
  STALE("stale");

  private final String token;

  CacheTier(String token) {
    this.token = token;
  }

  String token() {
    return token;
  }
}
