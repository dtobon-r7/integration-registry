# Contract fixture provenance

`openapi.json` is a verbatim copy of the locked Read API contract from the
engagement repo:

    engagements/unified-integrations-view/decisions/rfc/openapi.json

Copied 2026-06-04 for Track 09 / Work Plan 01 (response contract and
serialization). This service repo is self-contained; the test suite validates
DTO serialization against this fixture rather than reaching outside the repo.

When the engagement contract changes, re-copy this file and re-run the full
DTO serialization suite:
`./mvnw test -Dtest='com.rapid7.integrationregistry.controller.dto.*Test'`.
