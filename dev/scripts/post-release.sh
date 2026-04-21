#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/../.."

if [ $# -ne 1 ]; then
    echo "Usage: $0 <next-snapshot-version>"
    echo "Example: $0 0.2.0-SNAPSHOT"
    exit 1
fi

NEXT_VERSION="$1"

if [[ "$NEXT_VERSION" != *-SNAPSHOT ]]; then
    echo "Error: Next version must end with -SNAPSHOT"
    exit 1
fi

cd "$PROJECT_ROOT"

echo "==> Setting Maven version to $NEXT_VERSION"
./mvnw versions:set -DnewVersion="$NEXT_VERSION" -DgenerateBackupPoms=false -q

echo "==> Resetting install manifests image tags to latest"
for dir in install/*/base; do
    module=$(basename "$(dirname "$dir")")
    image="quay.io/streamshub/$module"
    echo "    $dir: $image:latest"
    (cd "$dir" && kustomize edit set image "$image=$image:latest")
done

echo "==> Committing next development cycle"
git add .
git commit -m "Prepare $NEXT_VERSION development"

echo ""
echo "Done. Run: git push origin main"
