#!/usr/bin/env bash
# Upload a built runtime archive to its own GitHub Release and point the
# manifests at it. One release per runtimeVersion, so app releases that keep
# the same runtime reuse the same 158 MB archive.
#
#   tooling/publish-runtime.sh --dry-run
#   REPO=maiji1990/dsh-Go tooling/publish-runtime.sh
#
# Needs the built runtime under runtime/out (runtime/build-runtime.sh) and an
# authenticated `gh`. After this, build the normal APK; the offline flavors do
# not need the upload.
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUT="${RUNTIME_OUT:-$ROOT/runtime/out}"
ABI="${RUNTIME_ABI:-arm64-v8a}"
REPO="${REPO:-maiji1990/dsh-Go}"
DRY=0
[[ ${1:-} == --dry-run ]] && DRY=1

manifest="$OUT/$ABI/assets/runtime/manifest.json"
[[ -f $manifest ]] || { echo "publish-runtime: no $manifest (build runtime first)" >&2; exit 1; }
version=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["runtimeVersion"])' "$manifest")
archive_name=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["rootfs"])' "$manifest")
archive="$OUT/$ABI/assets/runtime/$archive_name"
[[ -f $archive ]] || { echo "publish-runtime: no $archive" >&2; exit 1; }
engine_name=$(python3 -c 'import json,sys; print((json.load(open(sys.argv[1])).get("engine") or {}).get("archive",""))' "$manifest")
engine=""
if [[ -n "$engine_name" ]]; then
  engine="$OUT/$ABI/assets/runtime/$engine_name"
  [[ -f $engine ]] || { echo "publish-runtime: no $engine" >&2; exit 1; }
fi
tag="runtime-$version"
base="https://github.com/$REPO/releases/download/$tag"

if ((DRY)); then
  echo "would create/reuse release $tag and upload $archive_name ($(du -h "$archive" | cut -f1))"
  [[ -n "$engine" ]] && echo "would also upload $engine_name ($(du -h "$engine" | cut -f1))"
  echo "would run: runtime/set-runtime-url.sh $base"
  exit 0
fi

command -v gh >/dev/null 2>&1 || { echo "publish-runtime: gh not found" >&2; exit 1; }
if ! gh release view "$tag" --repo "$REPO" >/dev/null 2>&1; then
  gh release create "$tag" --repo "$REPO" --latest=false --prerelease \
    --title "Runtime $version" \
    --notes "DSH Go runtime $version. Referenced by the app on first run; do not edit or delete while a release uses it."
fi
gh release upload "$tag" "$archive" --repo "$REPO" --clobber
[[ -n "$engine" ]] && gh release upload "$tag" "$engine" --repo "$REPO" --clobber
"$ROOT/runtime/set-runtime-url.sh" "$base"
echo "runtime published: $base/$archive_name"
[[ -n "$engine" ]] && echo "engine published: $base/$engine_name"
echo "now build the normal APK with: tooling/build-apk.sh"
