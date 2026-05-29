package com.rapid7.integrationregistry.mapping.loader;

import com.rapid7.integrationregistry.mapping.BundleParser;
import com.rapid7.integrationregistry.mapping.VendorMappingSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

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
