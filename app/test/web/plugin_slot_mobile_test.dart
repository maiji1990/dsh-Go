import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  final host = File('assets/web/plugin_mobile.js').readAsStringSync();
  final css = File('assets/islands/island_mobile_components.css').readAsStringSync();
  test('official slots use the same controller and component stylesheet as islands', () {
    expect(host, contains('DshGoMobileLayout.create'));
    expect(host, contains('settings.section'));
    expect(host, contains('settings.general.item'));
    expect(host, contains('plugins.'));
    for (final chunk in css.replaceAll(RegExp(r'/\*[\s\S]*?\*/'), '').split('}')) {
      if (chunk.contains('{')) expect(chunk.trim(), startsWith(':is([data-dsh-mobile-root]'));
    }
  });
  test('layout disable, slot removal and pagehide stop controllers', () {
    expect(host, contains("hasAttribute('data-dsh-app-layout')"));
    expect(host, contains('!root.isConnected'));
    expect(host, contains('controller.stop()'));
    expect(host, contains("addEventListener('pagehide', stop)"));
    expect(host, isNot(contains('data-shortcut-modal')));
  });
}
