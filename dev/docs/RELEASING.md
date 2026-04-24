# Releasing

Releases are made from `release-*` branches using the `dev/scripts/release.sh` script
and the **Release** GitHub Actions workflow. The version in `pom.xml` is the single source
of truth — the container image tag and MCP server version are derived from it automatically.

Release branches use the `release-X.Y` naming convention (major.minor), so a single branch
handles all patch releases and release candidates for that minor version series.

## Release workflow

```bash
# 1. Create a release candidate (creates release-0.1 branch from main)
./dev/scripts/release.sh 0.1.0-RC1

# 2. Review the commit, then push to trigger CI
git push origin release-0.1

# 3. Wait for CI checks to pass (build + e2e) in the GitHub Actions tab.
#    Pushing to a release branch does NOT push a container image.

# 4. If fixes are needed: fix on main, cherry-pick to release branch
git checkout release-0.1
git cherry-pick <commit-sha>
git push origin release-0.1    # CI runs again, still no image push

# 5. Once CI is green, trigger the Release workflow:
#    - Go to Actions > Release in GitHub
#    - Click "Run workflow"
#    - Select branch: release-0.1
#    - Optionally add a description for the release notes
#    - Click "Run workflow"
#
#    This builds + pushes the container image, creates git tag v0.1.0-RC1,
#    and creates a GitHub Release (pre-release for RCs).

# 6. When ready for next RC, run the script again on the same branch
./dev/scripts/release.sh 0.1.0-RC2
git push origin release-0.1
# Wait for CI, then trigger Release workflow again

# 7. Final release
./dev/scripts/release.sh 0.1.0
git push origin release-0.1
# Wait for CI, then trigger Release workflow again (creates full release)

# 8. Bump main to next SNAPSHOT via a PR
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
# Wait for CI, then trigger Release workflow
```

## What the release script does

1. Validates version format
2. Creates or checks out the release branch (`release-X.Y`, derived from the major.minor components)
3. Sets all `pom.xml` versions via `mvn versions:set`
4. Updates kustomize base image tag in `install/strimzi-mcp/base/kustomization.yaml`
5. Verifies the build compiles
6. Commits — but does **not** push (you review first)

## What CI does on push to `release-*`

- **Build** (`build.yml`): compile + unit tests
- **E2E Tests** (`e2e.yml`): full system test suite on Kind cluster

Container images are **not** pushed on branch push. Use the Release workflow after CI passes.

## Release workflow (GitHub Actions)

The `release.yml` workflow is triggered manually from the GitHub Actions UI.
It must be run on a `release-X.Y` branch after CI checks have passed.

What it does:

1. Validates the branch matches `release-X.Y`
2. Reads the version from `pom.xml` and validates it is not a SNAPSHOT
3. Checks the git tag `v<version>` does not already exist
4. Builds and pushes the container image to `quay.io/streamshub/strimzi-mcp:<version>`
5. Creates and pushes git tag `v<version>`
6. Creates a GitHub Release with auto-generated notes
   - **Pre-release** if version contains `-` (e.g., `0.1.0-RC1`)
   - **Full release** otherwise (e.g., `0.1.0`, `0.1.1`)

Inputs:

- **description** (optional): text prepended to the auto-generated release notes

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
