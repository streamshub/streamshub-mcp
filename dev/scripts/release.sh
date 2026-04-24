#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/../.."

usage() {
    echo "Usage: $0 <release-version>"
    echo ""
    echo "Prepare a release by creating or updating a release branch with the"
    echo "given version, updating pom.xml and kustomize image tag."
    echo ""
    echo "The release branch name is derived from the major.minor components"
    echo "of the version (e.g., 0.1.0-RC1 -> release-0.1)."
    echo ""
    echo "  New branch:      creates release-X.Y from main"
    echo "  Existing branch: checks it out and bumps the version"
    echo ""
    echo "Examples:"
    echo "  $0 0.1.0-RC1    # creates release-0.1, sets version to 0.1.0-RC1"
    echo "  $0 0.1.0-RC2    # updates release-0.1, bumps to 0.1.0-RC2"
    echo "  $0 0.1.0        # updates release-0.1, sets final 0.1.0"
    echo "  $0 0.1.1        # updates release-0.1, sets patch 0.1.1"
    exit 1
}

if [ $# -ne 1 ]; then
    usage
fi

RELEASE_VERSION="$1"

if ! echo "$RELEASE_VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?$'; then
    echo "Error: Invalid version format: $RELEASE_VERSION"
    echo "Expected: X.Y.Z or X.Y.Z-qualifier (e.g., 0.1.0, 1.0.0-RC1)"
    exit 1
fi

MINOR_VERSION=$(echo "$RELEASE_VERSION" | sed 's/-.*//' | sed 's/\.[^.]*$//')
RELEASE_BRANCH="release-${MINOR_VERSION}"

cd "$PROJECT_ROOT"

if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "Error: Working tree has uncommitted changes. Commit or stash them first."
    exit 1
fi

if git ls-remote --exit-code --heads origin "$RELEASE_BRANCH" >/dev/null 2>&1; then
    echo "==> Branch '$RELEASE_BRANCH' exists, checking out and updating"
    git fetch origin "$RELEASE_BRANCH"
    git checkout "$RELEASE_BRANCH"
    git pull origin "$RELEASE_BRANCH"
else
    echo "==> Creating new branch '$RELEASE_BRANCH' from main"
    git checkout main
    git pull origin main
    git checkout -b "$RELEASE_BRANCH"
fi

echo "==> Setting version to $RELEASE_VERSION"
./mvnw versions:set \
    -DnewVersion="$RELEASE_VERSION" \
    -DgenerateBackupPoms=false \
    -q

(cd install/strimzi-mcp/base && kustomize edit set image "quay.io/streamshub/strimzi-mcp:${RELEASE_VERSION}")

echo "==> Verifying build compiles"
./mvnw package -DskipTests -Dquarkus.container-image.build=false -q

git add pom.xml '*/pom.xml' install/strimzi-mcp/base/kustomization.yaml
git commit -m "Release ${RELEASE_VERSION}"

echo ""
echo "Release ${RELEASE_VERSION} prepared on branch ${RELEASE_BRANCH}."
echo ""
echo "Review the commit:"
echo "  git log --oneline -1"
echo "  git diff HEAD~1"
echo ""
echo "When ready, push to trigger CI:"
echo "  git push origin ${RELEASE_BRANCH}"
