/**
 * Wire-format DTOs for the Integration Registry read API — the public JSON surface served by the
 * controller layer for the four read routes, plus the shared envelope ({@code
 * unavailable_products}, {@code metadata}) and {@code error} types.
 *
 * <p>These records are the authoritative serialization target locked by {@code
 * decisions/rfc/openapi.json}. They are deliberately separate from the {@code
 * aggregator.projection} records (the aggregator's internal output contract): the projections carry
 * internal-only fields ({@code data_source_id} on integrations, {@code vendor_services_count} on
 * the vendor-scoped view) and use Java-native enums, neither of which belong on the wire. Work Plan
 * 02 assembles projection values into these DTOs; Work Plan 03 returns them.
 *
 * <p>The package is self-contained — it imports nothing from {@code aggregator}, {@code mapping},
 * {@code adapter}, or {@code coordinator} (enforced by the {@code
 * controllerLayer_shouldNotDependOnInternalLayers} ArchUnit rule). snake_case wire names come from
 * a per-record {@code @JsonNaming(SnakeCaseStrategy)}; there is no global Jackson configuration.
 */
package com.rapid7.integrationregistry.controller.dto;
