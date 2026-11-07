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

  Map<String, Object?> scenario(String name) =>
      jsonDecode(File('test/plugins/islands/fixtures/$name.scenario.json').readAsStringSync())
          as Map<String, Object?>;

  Map<String, Object?> formScenario() => scenario('mobile_form');

  test('a plugin form page is reached through the settings section', () async {
    final out = await run(formScenario());
    expect(out['shown'], 'plugin');
    expect((out['messages'] as List).first, containsPair('t', 'ready'));
  }, skip: skip);

  test('diagnostics name the rules and the shell the island had to clear', () async {
    final out = await run(formScenario());
    final d = out['diagnostics'] as Map<String, Object?>;
    expect(d['slot'], 'settings.section');
    expect(d['mode'], 'page');
    expect(d['width'], 344);
    expect(d['rules'], ['card.restyle', 'flex.wrap']);
    // 0.2.2 marks what the official drawer covers inert; the plugin page only
    // works if the island clears the ancestors above its frame.
    expect(d['inertShell'], isTrue);
    expect(d['shellStillInert'], isFalse);
  }, skip: skip);

  test('two runs of one fixture report the same writes', () async {
    for (final name in ['mobile_form', 'mobile_table', 'mobile_dialog_tabs']) {
      final first = await run(scenario(name));
      final second = await run(scenario(name));
      expect(jsonEncode(second['changed']), jsonEncode(first['changed']), reason: name);
      expect(jsonEncode(second['diagnostics']), jsonEncode(first['diagnostics']), reason: name);
    }
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

  test('a card grid collapses, but an eight-column table is only scrolled', () async {
    final out = await run(scenario('mobile_table'));
    final byNode = {
      for (final c in (out['changed'] as List).cast<Map<String, Object?>>())
        c['node'] as String: c['props'] as Map<String, Object?>,
    };

    // The 3-up card grid is the shape the collapse rule is for.
    expect(byNode['cards'], containsPair('gridTemplateColumns', 'minmax(0, 1fr)'));
    // The filter chips wrap instead of being squeezed.
    expect(byNode['filters'], containsPair('flexWrap', 'wrap'));
    // The 8-column table is a record set: no rule touches it, it is only
    // marked as wide (island_mobile.css gives that a horizontal scroller).
    expect(byNode['data-table'], isNull);
    expect((out['wide'] as List), contains('data-table'));
  }, skip: skip);

  test('a fixed-size dialog keeps its height and loses its footer', () async {
    final out = await run(scenario('mobile_dialog_tabs'));
    final byNode = {
      for (final c in (out['changed'] as List).cast<Map<String, Object?>>())
        c['node'] as String: c['props'] as Map<String, Object?>,
    };

    // Nothing clamps a 620px dialog to the 360px frame: the footer sits below
    // the viewport. A `dialog.fit` rule is what PR-3 adds.
    expect(byNode['dialog'], isNull);
    expect(byNode['dialog-body'], isNull);
    // The tab strip only gets the generic flex wrap — it is not a horizontal
    // scroller and the selected tab is not brought into view.
    expect(byNode['tabs'], containsPair('flexWrap', 'wrap'));
    expect((byNode['tabs'] as Map).keys, isNot(contains('overflowX')));
  }, skip: skip);
}
