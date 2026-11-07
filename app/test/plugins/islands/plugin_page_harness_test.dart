// The `plugin` target of island_page_harness.mjs: a plugin's own page
// (fixtures/mobile_*.scenario.json, mirroring fixtures/mobile_*.html) drawn
// inside the official settings section, with island.js running over it under
// node. These assert the shared controller contract described in
// docs/design/plugin-mobile-adaptation.md:
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
    final base = jsonDecode(
      File('test/plugins/islands/fixtures/$name.scenario.json').readAsStringSync(),
    ) as Map<String, Object?>;
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
      expect(out['observersAfterHide'], 0);
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
  test('one live document widens and narrows without retaining adapted computed styles', () async {
    for (final (fixture, node) in [('mobile_form', 'row-api'), ('mobile_table', 'cards')]) {
      final wide = await run(
        scenario(fixture, {
          'mobileActions': [
            {'width': 800},
          ],
        }),
      );
      expect(role(wide, node)['compact'], isFalse);
      final narrow = await run(
        scenario(fixture, {
          'mobileActions': [
            {'width': 800},
            {'width': 360},
          ],
        }),
      );
      expect(role(narrow, node)['compact'], isTrue);
    }
  }, skip: skip);

  test('semantic adaptation can be disabled and reenabled on the same island', () async {
    final disabled = await run(
      scenario('mobile_form', {
        'mobileActions': [
          {'mode': 'compatibility'},
        ],
      }),
    );
    expect(disabled['roles'], isEmpty);
    final again = await run(
      scenario('mobile_form', {
        'mobileActions': [
          {'mode': 'compatibility'},
          {'mode': 'automatic'},
        ],
      }),
    );
    expect(role(again, 'row-api')['role'], 'form-row');
  }, skip: skip);

  test('new plugin subtrees are visited and removed nodes release their markers', () async {
    final added = await run(
      scenario('mobile_form', {
        'mobileActions': [
          {
            'node': 'page',
            'append': {
              'name': 'later-tabs',
              'attrs': {'role': 'tablist'},
              'kids': [],
            },
          },
        ],
      }),
    );
    expect(role(added, 'later-tabs')['role'], 'tablist');
    final removed = await run(
      scenario('mobile_form', {
        'mobileActions': [
          {'node': 'row-api', 'remove': true},
        ],
      }),
    );
    expect(role(removed, 'row-api'), isEmpty);
  }, skip: skip);

  test('a large plugin page processes nodes beyond 1200 in bounded batches', () async {
    final base = scenario('mobile_form');
    base['pluginDom'] = {
      'name': 'page',
      'box': {'width': 344, 'height': 900},
      'kids': [
        for (var i = 0; i < 1400; i++) {'name': 'node-$i'},
        {
          'name': 'last-tabs',
          'attrs': {'role': 'tablist'},
          'kids': [],
        },
      ],
    };
    final out = await run(base);
    expect(role(out, 'last-tabs')['role'], 'tablist');
    final stats = (out['mobile'] as List).cast<Map>().first;
    expect(stats['visited'], greaterThan(1400));
    expect(stats['maxBatchNodes'], lessThanOrEqualTo(200));
    expect(stats['pending'], 0);
  }, skip: skip);
  test('an unmarked data grid is not assumed to be cards', () async {
    final base = scenario('mobile_table');
    base['pluginDom'] = {
      'name': 'grid',
      'display': 'grid',
      'style': {'gridTemplateColumns': '100px 100px 100px'},
      'kids': [
        for (var i = 0; i < 9; i++) {'name': 'cell-$i', 'text': '$i'},
      ],
    };
    final out = await run(base);
    expect(role(out, 'grid'), isEmpty);
  }, skip: skip);

  test('a rule exception rolls back transformations and reports a stable reason', () async {
    final out = await run(scenario('mobile_form', {'throwAtNode': 'row-api'}));
    expect(out['roles'], isEmpty);
    final diagnostics = (out['mobile'] as List).cast<Map>().first;
    expect(diagnostics['mode'], 'compatibility');
    expect(diagnostics['reason'], 'rule-error');
    expect(jsonEncode(out['mobile']), isNot(contains('fixture error')));
  }, skip: skip);
}
