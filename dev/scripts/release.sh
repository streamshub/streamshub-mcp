#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/../.."

if [ $# -ne 1 ]; then
    echo "Usage: $0 <version>"
    echo "Example: $0 0.1.0"
    exit 1
fi

VERSION="$1"

if [[ "$VERSION" == *-SNAPSHOT ]]; then
    echo "Error: Release version must not contain -SNAPSHOT"
    exit 1
fi

cd "$PROJECT_ROOT"

echo "==> Setting Maven version to $VERSION"
./mvnw versions:set -DnewVersion="$VERSION" -DgenerateBackupPoms=false -q

echo "==> Updating install manifests image tags to $VERSION"
for dir in install/*/base; do
    module=$(basename "$(dirname "$dir")")
    image="quay.io/streamshub/$module"
    echo "    $dir: $image:$VERSION"
    (cd "$dir" && kustomize edit set image "$image=$image:$VERSION")
done

echo "==> Committing release"
git add .
git commit -m "Release $VERSION"
git tag "$VERSION"

echo ""
echo "Release $VERSION prepared. Next steps:"
echo "  git push origin main --tags"
echo "  Create GitHub Release from tag $VERSION"
echo "  Run: ./dev/scripts/post-release.sh <next-version>-SNAPSHOT"
