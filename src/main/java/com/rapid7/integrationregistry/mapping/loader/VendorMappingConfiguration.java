package com.rapid7.integrationregistry.mapping.loader;

import com.rapid7.integrationregistry.mapping.BundleParser;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * Spring configuration for the vendor-mapping runtime loader. Activates {@link
 * VendorMappingProperties} and wires the bean graph: {@link S3Client} (default credentials chain +
 * region; overridable in tests via {@code @MockitoBean}), {@link BundleParser} (Plan 02), the
 * snapshot holder (exposed both as {@link VendorMappingSnapshotHolder} for the listener and as
 * {@link VendorMappingSnapshot} for downstream consumers), the {@link S3VendorMappingBundleLoader},
 * and the {@link BundleLoadListener}.
 */
@Configuration
@EnableConfigurationProperties(VendorMappingProperties.class)
public class VendorMappingConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public S3Client s3Client() {
    return S3Client.create();
  }

  /**
   * Local-dev S3 client for LocalStack. LocalStack's in-cluster DNS does not resolve the
   * virtual-host bucket subdomain ({@code <bucket>.<host>}) that the SDK uses by default, and AWS
   * SDK for Java v2 has no env var / system property / profile knob for path-style addressing — it
   * is a builder-only option. This bean (active only under the {@code local} profile) forces
   * path-style and applies the {@code AWS_ENDPOINT_URL} override so the boot-time bundle load can
   * reach LocalStack. Production is unaffected: it has no {@code local} profile, so the
   * {@code @ConditionalOnMissingBean} default above remains the bean in every deployed environment.
   *
   * <p>Under the {@code local} profile two {@code S3Client} beans exist; {@code @Primary} on this
   * one is what disambiguates the by-type injection at the bundle loader — it does not rely on bean
   * declaration order. {@code endpointOverride} reads {@code AWS_ENDPOINT_URL}; credentials and
   * region come from the standard env chain (set to LocalStack dummies in service-config).
   */
  @Bean
  @Primary
  @Profile("local")
  public S3Client localStackS3Client() {
    String endpoint = System.getenv("AWS_ENDPOINT_URL");
    S3ClientBuilder builder = S3Client.builder().forcePathStyle(true);
    if (endpoint != null && !endpoint.isBlank()) {
      builder.endpointOverride(URI.create(endpoint));
    }
    return builder.build();
  }

  @Bean
  public BundleParser bundleParser() {
    return new BundleParser();
  }

  /**
   * Single holder bean. Because {@link VendorMappingSnapshotHolder} implements {@link
   * VendorMappingSnapshot}, Spring's by-type bean lookup resolves either type to this same instance
   * — downstream consumers inject {@code @Autowired VendorMappingSnapshot} while the listener /
   * health indicator inject {@code @Autowired VendorMappingSnapshotHolder}.
   *
   * <p>A separate {@code @Bean VendorMappingSnapshot} factory returning the same holder would
   * register a second bean of runtime type {@code VendorMappingSnapshotHolder} and break holder
   * autowiring.
   */
  @Bean
  public VendorMappingSnapshotHolder vendorMappingSnapshotHolder() {
    return new VendorMappingSnapshotHolder();
  }

  @Bean
  public S3VendorMappingBundleLoader bundleLoader(
      S3Client s3Client, BundleParser parser, VendorMappingProperties properties) {
    return new S3VendorMappingBundleLoader(s3Client, parser, properties);
  }

  @Bean
  public BundleLoadListener bundleLoadListener(
      S3VendorMappingBundleLoader loader,
      VendorMappingSnapshotHolder holder,
      VendorMappingProperties properties) {
    return new BundleLoadListener(loader, holder, properties);
  }

  @Bean
  public BundleLoadHealthIndicator bundleLoadHealthIndicator(
      VendorMappingSnapshotHolder holder, VendorMappingProperties properties) {
    return new BundleLoadHealthIndicator(holder, properties);
  }
}
