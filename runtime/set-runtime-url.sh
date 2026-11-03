#!/usr/bin/env bash
# Point the built runtime manifests at the host that serves the archives.
#
# Build the runtime first, upload `runtime/out/<abi>/assets/runtime/rootfs-*.tar.xz`
# to <base-url>/<file>, then run this before building the normal APK. The
# offline flavors keep the archive bundled and ignore the URL.
#
#   runtime/set-runtime-url.sh https://go.dshtui.com/runtime/r14-...
set -euo pipefail

BASE="${1:?usage: $0 <base-url-without-trailing-slash>}"
BASE="${BASE%/}"
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
OUT="${RUNTIME_OUT:-$HERE/out}"

python3 - "$OUT" "$BASE" <<'PY'
import json, os, sys

out, base = sys.argv[1], sys.argv[2]
found = False
for abi in ("arm64-v8a", "x86_64"):
    manifest = os.path.join(out, abi, "assets", "runtime", "manifest.json")
    if not os.path.isfile(manifest):
        continue
    data = json.load(open(manifest))
    data["rootfsUrl"] = base + "/" + data["rootfs"]
    if data.get("engine"):
        data["engine"]["url"] = base + "/" + data["engine"]["archive"]
    for path in (manifest, os.path.join(out, abi, "assets-manifest", "runtime", "manifest.json")):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            json.dump(data, f, indent=2)
            f.write("\n")
    print(f"{abi}: {data['rootfsUrl']}")
    if data.get("engine"):
        print(f"{abi}: {data['engine']['url']}")
    found = True

index_path = os.path.join(out, "manifest.json")
if os.path.isfile(index_path):
    index = json.load(open(index_path))
    for abi, entry in index.get("abis", {}).items():
        entry["rootfsUrl"] = base + "/" + entry["rootfs"]
        if entry.get("engine"):
            entry["engine"]["url"] = base + "/" + entry["engine"]["archive"]
    with open(index_path, "w") as f:
        json.dump(index, f, indent=2)
        f.write("\n")

if not found:
    sys.exit("no built runtime manifests under " + out)
PY
