# Releasing

Releases are made from `release-*` branches using the `dev/scripts/release.sh` script.
The version in `pom.xml` is the single source of truth — the container image tag and MCP
server version are derived from it automatically.

Release branches use the `release-X.Y` naming convention (major.minor), so a single branch
handles all patch releases and release candidates for that minor version series.

## Release workflow

```bash
# 1. Create a release candidate (creates release-0.1 branch from main)
./dev/scripts/release.sh 0.1.0-RC1

# 2. Review the commit, then push to trigger CI
git push origin release-0.1

# 3. If fixes are needed: fix on main, cherry-pick to release branch
git checkout release-0.1
git cherry-pick <commit-sha>
git push origin release-0.1    # CI rebuilds with current version

# 4. When ready for next RC, run the script again on the same branch
./dev/scripts/release.sh 0.1.0-RC2
git push origin release-0.1

# 5. Final release
./dev/scripts/release.sh 0.1.0
git push origin release-0.1

# 6. Create GitHub Release manually with changelog

# 7. Bump main to next SNAPSHOT via a PR
```

## Patch releases

Patch releases (e.g., `0.1.1`) use the same `release-0.1` branch:

```bash
# Cherry-pick fixes onto the release branch
git checkout release-0.1
git cherry-pick <commit-sha>

# Bump to patch version
./dev/scripts/release.sh 0.1.1
git push origin release-0.1
```

## What the script does

1. Validates version format
2. Creates or checks out the release branch (`release-X.Y`, derived from the major.minor components)
3. Sets all `pom.xml` versions via `mvn versions:set`
4. Updates kustomize base image tag in `install/strimzi-mcp/base/kustomization.yaml`
5. Verifies the build compiles
6. Commits — but does **not** push (you review first)

## What CI does on push to `release-*`

- **Build** (`build.yml`): compile + unit tests
- **Container Image** (`container-image.yml`): builds and pushes `quay.io/streamshub/strimzi-mcp:<version>` (tag from pom.xml)
- **E2E Tests** (`e2e.yml`): full system test suite on Kind cluster

## Version sources

```
pom.xml version (single source of truth)
  ├── MCP server version   (application.properties: ${quarkus.application.version})
  ├── Container image tag  (application.properties: ${quarkus.application.version:latest})
  └── Kustomize newTag     (updated by release.sh)
```

On `main`, the pom version is `X.Y.Z-SNAPSHOT` and the container image is tagged `latest`.
On `release-*` branches, the pom version is the release version and the image tag matches it.

## After the release

Create a PR on `main` to bump `pom.xml` to the next SNAPSHOT version (e.g., `0.2.0-SNAPSHOT`).
