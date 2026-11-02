#!/usr/bin/env bash
# Losslessly recompress the golden PNGs (OPT-06).
#
# Oxipng only changes the PNG encoding: pixels stay identical (it may drop a
# fully opaque alpha channel), so `matchesGoldenFile` keeps passing. The
# repository's history still holds the old blobs; a clone only gets the smaller
# files from this commit on.
#
#   tooling/optimize-goldens.sh
#   tooling/optimize-goldens.sh --dry-run   # list the files, change nothing
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$ROOT"

command -v oxipng >/dev/null 2>&1 || {
  echo "optimize-goldens: oxipng not found (https://github.com/oxipng/oxipng/releases)" >&2
  exit 1
}

dry=0
case "${1:-}" in
  --dry-run) dry=1 ;;
  '') ;;
  *) echo "usage: $0 [--dry-run]" >&2; exit 2 ;;
esac

mapfile -t files < <(git ls-files app/test | grep -E '^app/test/.*/goldens/[^/]+\.png$' || true)
((${#files[@]})) || { echo "optimize-goldens: no golden PNGs found" >&2; exit 1; }

echo "golden PNGs: ${#files[@]}"
if ((dry)); then
  printf '%s\n' "${files[@]}"
  exit 0
fi

before=$(printf '%s\0' "${files[@]}" | du -ch --files0-from=- | tail -n 1 | cut -f1)
oxipng -o4 --strip safe --quiet "${files[@]}"
after=$(printf '%s\0' "${files[@]}" | du -ch --files0-from=- | tail -n 1 | cut -f1)
echo "golden PNGs: $before -> $after"
echo "verify with: cd app && DSH_TEST_FONT_DIR=/usr/share/fonts/opentype/noto flutter test <golden test files>"
