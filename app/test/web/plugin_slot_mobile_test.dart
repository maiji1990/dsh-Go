// The plugin-slot rules at the end of dsh_web_phone.css: the same three
// behaviours the island gets from island.js (form.stack, toolbar.wrap,
// tab.scroll), expressed without a runtime because the official Web UI runs
// upstream's dsh_web_phone.js, not ours. Structure only, so every promise is
// checked statically here:
//
//   - every selector is scoped to a plugin slot AND to the App's phone layout
//     (网页版手机布局 off removes data-dsh-app-layout, web_view_page.dart);
//   - the whole section is behind one @supports (:has), so an older WebView
//     gets the boundary layer and never a half-applied rule;
//   - nothing touches the official settings modal or anything outside a slot,
//     and dialogs are deliberately absent (see the comment in the CSS).

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  final css = File('assets/web/dsh_web_phone.css').readAsStringSync();
  final start = css.indexOf('/* ── Plugin slots, structure only');

  test('the section exists and is last', () {
    expect(start, greaterThan(0), reason: 'the plugin-slot section is gone');
    expect(css.indexOf('/* ── ', start + 10), -1,
        reason: 'another section follows; the guards below assume this is the end');
  });

  // The section's own comment states the promises; the checks are about rules.
  final rules = css.substring(start).replaceAll(RegExp(r'/\*[\s\S]*?\*/'), '');

  test('every selector is scoped to the phone layout and a plugin slot', () {
    final selectors = [
      for (final chunk in rules.split('}'))
        if (chunk.contains('{')) chunk.substring(0, chunk.indexOf('{')).trim(),
    ].where((s) => !s.startsWith('@')).toList();
    expect(selectors, isNotEmpty);
    for (final selector in selectors) {
      final oneLine = selector.replaceAll('\n', ' ').trim();
      expect(
        oneLine,
        startsWith(":root[data-dsh-app-layout='drawer']"),
        reason: 'unscoped selector: $oneLine',
      );
      expect(
        oneLine,
        contains(RegExp(r"data-slot=('settings\.section'|'settings\.general\.item')|data-slot\^='plugins\.'")),
        reason: 'a rule outside the plugin slots: $oneLine',
      );
    }
  });

  test('the section is exactly one @supports guard', () {
    expect(RegExp(r'@supports').allMatches(rules).length, 1);
    expect(rules.indexOf('@supports'), lessThan(rules.indexOf(':root[')),
        reason: 'a rule outside the guard');
    expect(rules.trim().endsWith('}'), isTrue, reason: 'the guard is not closed');
  });

  test('dialogs, the official modal and island roles are untouched', () {
    expect(rules, isNot(contains('dialog')));
    expect(rules, isNot(contains('data-shortcut-modal')));
    expect(rules, isNot(contains('data-dsh-go-island')));
    expect(rules, isNot(contains('data-dsh-mobile-role')),
        reason: 'the official page has no runtime of ours to set roles');
  });

  test('table.scroll stays the unguarded boundary rule further up', () {
    // It works on every WebView, which is why it is not part of :has() here.
    expect(css.substring(0, start), contains(') table {'));
    expect(rules, isNot(contains('table')));
  });
}
