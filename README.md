# Zalava Docker module

An independently released Zalava provider module for Docker.

## Build and verify

The module resolves the released public Zalava API and contract kit from
`https://zalava.github.io/zalava-maven/` by default:

```bash
./gradlew check
```

The test suite loads the built JAR through the released contract kit. It is
module-repository acceptance; installation, authorization and persistence are
verified by the Zalava host.

## Release

The reviewed alpha version in `module-metadata.yaml` is the source of truth.
A release tag must match that version. The release workflow publishes the JAR
and its SHA-256 sidecar as immutable GitHub Release assets.
