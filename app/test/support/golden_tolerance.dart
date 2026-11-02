import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';

/// `Pixel test failed, 0.09%, 359px diff detected.`
final RegExp _pixelFailure = RegExp(r'Pixel test failed, ([\d.]+)%');

/// A golden comparator that tolerates a small share of differing pixels.
///
/// Inline maths in the share cards (`Δt < 0.3`) is drawn through a synthesised
/// italic, and its anti-aliasing differs by a handful of pixels between CPUs:
/// CI and a developer machine disagree by ~0.09% on otherwise identical cards.
/// Anything above [maxDiffPercent] still fails.
class TolerantGoldenComparator extends GoldenFileComparator {
  TolerantGoldenComparator(this.inner, this.maxDiffPercent);

  final GoldenFileComparator inner;

  /// Share of pixels that may differ, e.g. `0.0015` for 0.15%.
  final double maxDiffPercent;

  @override
  Future<bool> compare(Uint8List imageBytes, Uri golden) async {
    try {
      return await inner.compare(imageBytes, golden);
    } on FlutterError catch (error) {
      final match = _pixelFailure.firstMatch(error.message);
      final percent = match == null ? null : double.tryParse(match.group(1)!);
      if (percent != null && percent <= maxDiffPercent * 100) return true;
      rethrow;
    }
  }

  @override
  Future<void> update(Uri golden, Uint8List imageBytes) => inner.update(golden, imageBytes);

  @override
  Uri getTestUri(Uri key, int? version) => inner.getTestUri(key, version);
}

/// Installs [TolerantGoldenComparator] for this test file.
void tolerateGoldenPixelDiff(double maxDiffPercent) {
  final current = goldenFileComparator;
  if (current is TolerantGoldenComparator) return;
  goldenFileComparator = TolerantGoldenComparator(current, maxDiffPercent);
}
