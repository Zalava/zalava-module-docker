# Zalava contributor guidance

## Bounded contexts and ports

Organize product code by bounded context / feature slice. A context owns its
domain language, use cases, and persistence-facing contracts; it does not reach
into another context's internals. Share a stable contract only when the
relationship is deliberate and versioned.

Keep each context hexagonal. Domain rules must not import Spring, HTTP,
persistence, messaging, or vendor-SDK types. Inbound adapters translate a
transport request into a use-case input. Use cases coordinate domain behavior
through explicit inbound and outbound ports. Outbound adapters implement those
ports and contain framework and infrastructure mapping. Dependencies point
inward, and Spring wiring remains at a composition boundary.

Do not introduce a Gradle subproject merely to represent a context. Split build
modules only when independent compilation, release, ownership, or dependency
isolation requires it. Prefer an incremental vertical-slice migration to a
broad architectural rewrite.

Zalava is a Java 25 Gradle multi-project application with a Spring Boot host,
PostgreSQL persistence, and independently released JVM modules.

Run the Gradle wrapper from the repository root. Use focused tests first and the
documented verification task before review. Keep tests deterministic and test
observable behavior; HTTP behavior uses full-context MockMvc tests and product
journeys use the repository browser acceptance lane when available.

Keep policy and validation at system boundaries. External modules compile against
the released Module API, must not bundle it, and preserve declared identifiers
unless a reviewed compatibility change says otherwise. Do not commit credentials,
user data, local configuration, generated browser output, or build products.
