# Zalava architecture

## Context boundaries

Organize product code around bounded contexts rather than technical layers.
Each context owns its domain model, use cases, and persistence-facing contracts.
Cross-context collaboration uses a deliberately stable contract; contexts do
not import one another's internal adapters or persistence models.

Within a context, inbound adapters translate HTTP, jobs, commands, or other
transport into use-case inputs. Use cases apply domain behavior and use explicit
outbound ports. Outbound adapters implement those ports and contain framework,
database, messaging, and SDK mapping. Domain and use-case code stays independent
of those technologies. The composition root supplies concrete adapters.

A bounded context is a source-level ownership boundary, not automatically a
Gradle subproject. Introduce a separate build module only for a demonstrated
need such as independent release, compilation, ownership, or dependency
isolation.

The public repository is a Gradle multi-project build: `module-api` is the stable external-module contract, `module-api-test` is the module contract-test kit, and `app` is the Spring Boot host and adapters.

The host owns policy enforcement, validation, persistence, lifecycle, authorization, and audit boundaries. Business capabilities use explicit ports; adapters connect frameworks and external systems. Modules supply provider factories and instances, allowing configuration, permission, lifecycle, and audit decisions to attach to the provider that performs work. Modules do not depend on each other directly or receive arbitrary host application objects.
