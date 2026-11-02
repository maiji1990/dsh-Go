// A plugin that ships its own settings page (`autoGenerate: false`) opens that
// page in a web island when the bridge reports its `settings.section` seat;
// without an island the native form is still there.

// ignore_for_file: depend_on_referenced_packages
import 'package:dsh_app/plugins/seams/seams.dart';
import 'package:dsh_app/ui/plugins/config/plugin_config_page.dart';
import 'package:dsh_app/ui/plugins/islands/islands.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:webview_flutter_platform_interface/webview_flutter_platform_interface.dart';

import '../engine_settings/support.dart' show pumpPage;
import '../plugins/islands/support/fakes.dart';
import 'support.dart';

const _package = '@acme/dsh-weather';

SeatInventory _inventory({bool web = true}) => SeatInventory(
  webReachable: web,
  routeGuard: true,
  packages: const {_package: PluginClientInfo(package: _package, hasClient: true, enabled: true, installed: true)},
  occupancy: const {
    Slots.settingsSection: [SlotOccupant(slot: Slots.settingsSection, package: _package, cell: 'weather-settings')],
  },
);

void main() {
  late FakeWebViewPlatform web;
  late IslandHost host;

  setUp(() {
    web = FakeWebViewPlatform();
    WebViewPlatform.instance = web;
    host = IslandHost(platform: FakeIslandPlatform(), assets: TestIslandAssets());
  });

  tearDown(() => host.dispose());

  Future<void> open(WidgetTester tester, {bool reachable = true, bool islands = true}) => pumpPage(
    tester,
    const PluginConfigPage(ns: 'weather'),
    overrides: [
      ...configOverrides(sampleConfigApi()),
      seatInventoryProvider.overrideWith((ref) => _inventory(web: reachable)),
      islandBudgetProvider.overrideWithValue(islands ? const OpenIslandBudget() : const NoIslandBudget()),
      islandHostProvider.overrideWith((ref) => host),
      islandHostUrlProvider.overrideWith((ref, host) => Uri.parse('http://127.0.0.1:3080/')),
    ],
    height: 1400,
  );

  testWidgets('a reported settings section opens the plugin page as an island', (tester) async {
    await open(tester);
    expect(find.byType(IslandView), findsOneWidget);
    expect(find.text('城市'), findsNothing, reason: 'the native form is not drawn under the island');
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('without an island the native form and the web way remain', (tester) async {
    await open(tester, reachable: false);
    expect(find.byType(IslandView), findsNothing);
    expect(find.text('城市'), findsOneWidget);
    expect(find.text('在网页版设置'), findsOneWidget);
  });

  testWidgets('no island budget keeps the native form', (tester) async {
    await open(tester, islands: false);
    expect(find.byType(IslandView), findsNothing);
    expect(find.text('城市'), findsOneWidget);
  });
}
