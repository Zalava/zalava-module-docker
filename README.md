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

## Catalog discovery and release indexes

The Zalava catalog locates this source repository and `releases/index.yaml`.
Versions, artifacts, digests, source commits, permissions and compatibility belong
to this module's release index. Do not update the catalog for each release.

The release-index workflow verifies every indexed artifact against its anonymous
GitHub Release download and immutable source tag. On a newly published release it
generates a reviewed index-update PR; merge that PR to expose the release through
catalog discovery. Enable Actions to create pull requests in repository settings.
The proposing workflow verifies the exact generated index. Maintainers must run
the normal module gate before approving it. The workflow never merges.

For manual generation, install `scripts/requirements.txt`, then run
`python scripts/release_index.py --artifact <published-jar> --tag <immutable-tag>
--revision <full-tag-commit> --repository https://github.com/Zalava/<repository>`.
Existing version entries cannot be rewritten. JSON syntax in `index.yaml` is valid
YAML and is accepted by the host's strict release-index loader.

The index workflow is called directly by the release workflow after publishing,
so GitHub token event suppression does not prevent index maintenance. Every
indexed source tag must belong to public `main`. Index updates remain reviewed
PRs; repository Actions permissions must allow their creation, and automation
never merges them. GitHub may require approval before running bot-created PR CI.
