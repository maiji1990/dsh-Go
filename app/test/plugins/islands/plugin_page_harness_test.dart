// The `plugin` target of island_page_harness.mjs: a plugin's own page
// (fixtures/mobile_*.scenario.json, mirroring fixtures/mobile_*.html) drawn
// inside the official settings section, with island.js running over it under
// node. PR-3 of docs/design/plugin-mobile-adaptation.md is what these assert:
// island.js classifies plugin content into named roles, writes only data
// attributes, lets island_mobile_components.css lay it out, and drops every
// attribute it wrote when the page goes away.
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

  Map<String, Object?> scenario(String name, [Map<String, Object?> extra = const {}]) {
    final base =
        jsonDecode(File('test/plugins/islands/fixtures/$name.scenario.json').readAsStringSync())
            as Map<String, Object?>;
    return {...base, ...extra};
  }

  Map<String, Object?> role(Map<String, Object?> out, String node) =>
      (out['roles'] as Map<String, Object?>)[node] as Map<String, Object?>? ?? const {};

  test('a plugin form page is reached through the settings section', () async {
    final out = await run(scenario('mobile_form'));
    expect(out['shown'], 'plugin');
    expect((out['messages'] as List).first, containsPair('t', 'ready'));
  }, skip: skip);

  test('a label beside its control becomes a form row, not a wrapped flex row', () async {
    final out = await run(scenario('mobile_form'));
    expect(role(out, 'row-api'), {'role': 'form-row', 'compact': true});
    expect(role(out, 'row-model'), {'role': 'form-row', 'compact': true});
    // A row of buttons is a toolbar; both are CSS-driven, so no inline style.
    expect(role(out, 'toolbar'), {'role': 'toolbar', 'compact': true});
    expect(out['changed'], isEmpty, reason: 'the adapter must not write inline styles');
  }, skip: skip);

  test('a card is left alone: no restyle, no permanent marker', () async {
    final out = await run(scenario('mobile_form'));
    expect((out['cards'] as List), isEmpty);
    expect(role(out, 'card-summary'), isEmpty, reason: 'a full-width box is not a card grid');
    expect(out['changed'], isEmpty);
  }, skip: skip);

  test('a card grid collapses while an eight-column table only scrolls', () async {
    final out = await run(scenario('mobile_table'));
    expect(role(out, 'cards'), {'role': 'card-grid', 'compact': true});
    expect(role(out, 'data-table'), {'role': 'table', 'compact': false});
    // The table keeps its width; island_mobile_components.css gives it a scroller.
    expect(role(out, 'data-table')['role'], isNot('card-grid'));
    expect(out['changed'], isEmpty);
  }, skip: skip);

  test('a dialog and a tab strip get the rules that keep their actions reachable', () async {
    final out = await run(scenario('mobile_dialog_tabs'));
    expect(role(out, 'dialog'), {'role': 'dialog', 'compact': true});
    expect(role(out, 'tabs'), {'role': 'tablist', 'compact': true});
    expect(role(out, 'dialog-footer'), {'role': 'toolbar', 'compact': true});
    expect(out['changed'], isEmpty);
  }, skip: skip);

  test('diagnostics name the rules that fired', () async {
    final form = (await run(scenario('mobile_form')))['diagnostics'] as Map<String, Object?>;
    expect(form['rules'], ['form.stack', 'toolbar.wrap']);
    expect(form['inertShell'], isTrue);
    expect(form['shellStillInert'], isFalse);

    final table = (await run(scenario('mobile_table')))['diagnostics'] as Map<String, Object?>;
    expect(table['rules'], ['card-grid.collapse', 'table.scroll', 'toolbar.wrap']);

    final dialog = (await run(scenario('mobile_dialog_tabs')))['diagnostics'] as Map<String, Object?>;
    expect(dialog['rules'], ['dialog.fit', 'tab.scroll', 'toolbar.wrap']);
  }, skip: skip);

  test('leaving the page drops every attribute the adapter wrote', () async {
    for (final name in ['mobile_form', 'mobile_table', 'mobile_dialog_tabs']) {
      final out = await run(scenario(name, {'pagehide': true}));
      expect(out['roles'], isEmpty, reason: name);
      expect(out['residual'], isEmpty, reason: name);
    }
  }, skip: skip);

  test('two runs of one fixture report the same writes', () async {
    for (final name in ['mobile_form', 'mobile_table', 'mobile_dialog_tabs']) {
      final first = await run(scenario(name));
      final second = await run(scenario(name));
      expect(jsonEncode(second['roles']), jsonEncode(first['roles']), reason: name);
      expect(jsonEncode(second['diagnostics']), jsonEncode(first['diagnostics']), reason: name);
    }
  }, skip: skip);
}
