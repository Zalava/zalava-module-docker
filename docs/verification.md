# Module verification


## Verification and delivery

Run `GRADLE_USER_HOME=/tmp/gradle-home ./gradlew check` before review or release.
`check` must enforce Spotless 7.2.1 / google-java-format 1.36.1 and JaCoCo
minimums of 90% line coverage and 74% branch coverage, matching the Zalava host.
Do not lower thresholds or exclude uncovered production classes to pass.
Use focused tests first and retain coverage reports and failure diagnostics.

Test the real built module JAR through the released `org.zalava:module-api-test`
kit, not injected module classes or source-output directories. Cover providers,
typed services, module pages/forms/assets, configuration, validation/failure,
permissions, cleanup and observable effects as applicable. Keep fixtures local
and deterministic. Module UI actions must not require AI mediation.
Contract-kit acceptance is not real-Zalava acceptance: Core owns installation,
configuration, restart, security, persistence and browser journeys against
released artifacts. Record missing prerequisites; never count them as passing.

Production code must remain independent of host internals. Compile against the
released Module API, never bundle it, preserve declared wire identifiers, and
pin compatible API/kit versions. Runtime/client SDKs belong in adapters; domain
rules and use cases communicate through explicit contracts.

Use an explicit step branch, dated plan/evidence, scoped tests, staged-diff review
and a ready-for-review PR. Manage dependent PRs with `gh stack`; never merge
autonomously. Publish immutable versions only from verified merged default-branch
commits through successful publication workflows. A tag alone is not a release.

Adapter unit tests run in the separate `unitTest` lane and contribute to the
aggregate coverage gate. They do not replace or alter the isolated built-JAR
contract lane in `test`, and are not reported as artifact acceptance.
