// The `plugin` target of island_page_harness.mjs: a plugin's own settings page
// (fixtures/mobile_form.scenario.json, mirroring fixtures/mobile_form.html)
// drawn inside the official settings section, with island.js running over it
// under node. This pins what today's rules do to arbitrary plugin content, so
// PR-3 (docs/design/plugin-mobile-adaptation.md) can show the difference.
//
// Today, and this is what the test records:
//   - the toolbar and both label/control rows get an inline `flexWrap: wrap`,
//     which wraps the row but leaves the 140px right-aligned label on its own
//     line (not L1: the control is still squeezed off the frame);
//   - the card is restyled (background, border, radius) and keeps a permanent
//     `data-dsh-go-card` marker, which the design doc says should not happen;
//   - nothing is ever undone: `residual` lists every node the island touched.
//
// Needs `node` on PATH; skipped without it.

import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

String? _node() {
  for (final dir in (Platform.environment['PATH'] ?? '').split(':')) {
    if (dir.isNotEmpty && File('$dir/node').existsSync()) return '$dir/node';
  }
  return null;
}

void main() {
  final node = _node();
  final skip = node == null ? 'node is not installed' : null;

  Future<Map<String, Object?>> run(Map<String, Object?> scenario) async {
    final result = await Process.run(node!, [
      'test/plugins/islands/support/island_page_harness.mjs',
      'assets/islands/island.js',
      jsonEncode(scenario),
    ]);
    expect(result.exitCode, 0, reason: '${result.stderr}');
    final out = jsonDecode(result.stdout as String) as Map<String, Object?>;
    expect(out['thrown'], isNull, reason: '${out['thrown']}');
    return out;
  }

  Map<String, Object?> formScenario() =>
      jsonDecode(File('test/plugins/islands/fixtures/mobile_form.scenario.json').readAsStringSync())
          as Map<String, Object?>;

  test('a plugin form page is reached through the settings section', () async {
    final out = await run(formScenario());
    expect(out['shown'], 'plugin');
    expect((out['messages'] as List).first, containsPair('t', 'ready'));
  }, skip: skip);

  test('today\'s rules wrap the rows instead of stacking them', () async {
    final out = await run(formScenario());
    final changed = (out['changed'] as List).cast<Map<String, Object?>>();
    final byNode = {for (final c in changed) c['node'] as String: c['props'] as Map<String, Object?>};

    // The toolbar and both label/control rows: wrapped, not stacked.
    expect(byNode['toolbar'], containsPair('flexWrap', 'wrap'));
    expect(byNode['row-api'], containsPair('flexWrap', 'wrap'));
    expect(byNode['row-model'], containsPair('flexWrap', 'wrap'));
    // A `form.stack` rule would move the label above the control; nothing does.
    expect(byNode['label-api'], isNull);
  }, skip: skip);

  test('a card is restyled and marked, and nothing is undone', () async {
    final out = await run(formScenario());
    // PR-3 removes this restyle (the design says adaptation never restyles a
    // look); until then the marker and the inline colours are what happens.
    expect((out['cards'] as List), contains('card-summary'));
    final card = (out['changed'] as List)
        .cast<Map<String, Object?>>()
        .firstWhere((c) => c['node'] == 'card-summary');
    expect((card['props'] as Map<String, Object?>).keys, containsAll(['backgroundColor', 'borderRadius']));
    // No undo path at all: everything the island touched stays touched.
    expect((out['residual'] as List), isNotEmpty);
  }, skip: skip);
}
