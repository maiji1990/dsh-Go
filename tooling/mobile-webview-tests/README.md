# Plugin mobile layout WebView regression

This standalone Android application has its own package (`io.github.dshgo.mobiletest`), no runtime, accounts or host connection. It tests the current production JavaScript/CSS copied by a Gradle `Sync` task, with synthetic plugin fixtures. It never installs over dsh-Go. Its images are drawn from its own WebView into a canvas; it does not capture the device screen.

From the repository root, with JDK 17, Android SDK 36 and adb on PATH:

```bash
# On this checkout, source tooling/env.sh first.
# Set ANDROID_SERIAL when more than one target is attached.
tooling/test-mobile-webview.sh /tmp/plugin-mobile-report
```

The script uses the app's local Gradle wrapper, or system Gradle 9.3.1 in CI. It builds, installs, runs instrumentation and retrieves fixture images plus JSON. It fails if an assertion fails, the report is missing, or its asset SHA-256 values differ from the source tree. `CI OK` includes an Android 11/15 emulator matrix; the Flutter and Node tests run in existing CI jobs.

The suite covers real CSS and WebView layout at 320/360/393/480/600/768/1024 px, narrowing and widening, field rows, table scrollports, card grids, slot lifecycle/scope, dynamic DOM, nodes beyond 1200, 100 restarts and 30 applied rotations. Input fixtures include text, password, email, URL, phone, search, number, textarea, select, date, time, color, file, checkbox, radio and range controls. Tests type synthetic values, check focus/selection, return from native pickers, and verify save accessibility at 200% text zoom.

This suite does not by itself certify Flutter's platform-view gesture arbitration, a real plugin's business behavior, TalkBack quality, host disconnection or low-memory renderer loss. The app's existing tests cover fallback ownership and hit regions; release acceptance still needs the documented end-to-end checks.
