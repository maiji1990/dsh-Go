#!/usr/bin/env bash
# Isolated Android WebView regression APK; never installs over dsh-Go.
set -euo pipefail
project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
report_dir=${1:-$(mktemp -d /tmp/dsh-mobile-webview.XXXXXX)}
mkdir -p "$report_dir"
device=()
if [[ -n ${ANDROID_SERIAL:-} ]]; then device=(-s "$ANDROID_SERIAL"); fi
if [[ -x "$project_root/app/android/gradlew" ]]; then
  gradle_cmd=("$project_root/app/android/gradlew")
else
  gradle_cmd=(gradle)
fi
"${gradle_cmd[@]}" -p "$project_root/tooling/mobile-webview-tests" assembleDebug --console=plain
adb "${device[@]}" install -r "$project_root/tooling/mobile-webview-tests/build/outputs/apk/debug/MobileWebViewTests-debug.apk"
# Android 11+ blocks the shell user from Android/data (API 30 failed the pull), and
# run-as cannot reach it either. The test app writes its report and screenshots to
# internal storage, which the shell reaches through run-as on every API level.
pkg=io.github.dshgo.mobiletest
files=/data/data/$pkg/files
adb "${device[@]}" shell run-as "$pkg" rm -f "$files/report.json"
adb "${device[@]}" shell am instrument -w io.github.dshgo.mobiletest/.MobileInstrumentation > "$report_dir/instrumentation.txt"
mkdir -p "$report_dir/files"
adb "${device[@]}" exec-out run-as "$pkg" tar -cf - -C "$files" . > "$report_dir/files.tar"
tar -xf "$report_dir/files.tar" -C "$report_dir/files"
rm -f "$report_dir/files.tar"
python3 - "$report_dir/files/report.json" "$project_root" <<'PY'
import hashlib, json, pathlib, sys
report = json.loads(pathlib.Path(sys.argv[1]).read_text())
root = pathlib.Path(sys.argv[2])
for name, actual in report['assets'].items():
    source = root / 'app/assets' / ('web' if name == 'plugin_mobile.js' else 'islands') / name
    assert hashlib.sha256(source.read_bytes()).hexdigest() == actual, f'stale asset: {name}'
assert report['passed'] and report['checks'] and all(c['pass'] for c in report['checks']), report
print(f"Android {report['sdk']}, WebView {report['webView']}: {len(report['checks'])} checks passed")
PY
