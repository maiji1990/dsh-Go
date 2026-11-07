// The native seats at the narrowest phone the app supports (320 logical px)
// with the largest text the system can ask for (2.0x), plus the insets a page
// island leaves for the gesture bar and the landscape notch. This is PR-2 of
// docs/design/plugin-mobile-adaptation.md: L1 means the rows and the page
// chrome stay operable, not just visible.

import 'package:dsh_app/plugins/seams/seams.dart';
import 'package:dsh_app/runtime/runtime_state.dart';
import 'package:dsh_app/state/settings_state.dart';
import 'package:dsh_app/ui/plugins/seats/plugin_seat_page.dart';
import 'package:dsh_app/ui/plugins/seats/seat_island.dart' show pageInsetsFor;
import 'package:dsh_app/ui/settings/settings_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';

import '../../shell/golden_harness.dart';
import '../seats/support.dart';

/// 320×640, 2× text, a 24pt gesture bar — and whatever insets a real phone
/// reports on the sides.
void useNarrowSurface(WidgetTester tester, {double width = 320, double textScale = 2.0, double side = 0}) {
  tester.view.devicePixelRatio = 2;
  tester.view.physicalSize = Size(width * 2, 640 * 2);
  tester.view.padding = FakeViewPadding(top: 32 * 2, bottom: 24 * 2, left: side * 2, right: side * 2);
  tester.view.viewPadding = FakeViewPadding(top: 32 * 2, bottom: 24 * 2, left: side * 2, right: side * 2);
  tester.platformDispatcher.textScaleFactorTestValue = textScale;
  addTearDown(tester.view.reset);
  addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
}

final _hub = <Override>[
  localRuntimeProvider.overrideWith(() => FakeRuntime(const LocalRuntimeState(loaded: true, status: runningStatus))),
  settingsProvider.overrideWith(() => FakeSettings(const AppSettings(loaded: true))),
];

Future<void> _pump(WidgetTester tester, Widget home, {List<Override> extra = const []}) async {
  await tester.pumpWidget(
    goldenApp(
      home: home,
      brightness: Brightness.light,
      overrides: [
        ...extra,
        ...seatOverrides(inventory: seatInventory(occupancy: fullOccupancy)),
      ],
    ),
  );
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 600));
}

void main() {
  test('a page island keeps the gesture bar and the landscape notch clear', () {
    final portrait = const MediaQueryData(size: Size(390, 844), padding: EdgeInsets.only(top: 32, bottom: 24));
    expect(pageInsetsFor(portrait), const EdgeInsets.only(bottom: 24));

    final landscape = const MediaQueryData(
      size: Size(844, 390),
      padding: EdgeInsets.only(left: 44, right: 44, bottom: 20),
    );
    expect(pageInsetsFor(landscape), const EdgeInsets.only(left: 44, right: 44, bottom: 20));
  });

  for (final width in [320.0, 360.0, 393.0]) {
    testWidgets('a plugin island page survives ${width.toInt()}px and 2x text', (tester) async {
      useNarrowSurface(tester, width: width);
      await _pump(
        tester,
        const PluginSeatPage(
          title: '岛屿探针',
          request: SeatRequest(
            slot: Slots.settingsSection,
            placement: SeatPlacement.page,
            package: probe,
            cell: 'k4-probe',
          ),
        ),
      );
      expect(tester.takeException(), isNull, reason: 'a row overflowed at ${width.toInt()}px / 2x');
      expect(find.text('岛屿探针'), findsWidgets);
    });
  }

  for (final width in [320.0, 360.0, 393.0]) {
    testWidgets('a page that only opens in DSH Web keeps its way out at ${width.toInt()}px / 2x', (tester) async {
      useNarrowSurface(tester, width: width);
      await _pump(
        tester,
        const PluginSeatPage(
          title: '桌宠面板',
          request: SeatRequest(slot: Slots.main, placement: SeatPlacement.page, package: pet, cell: 'pet-panel'),
        ),
      );
      expect(tester.takeException(), isNull, reason: 'a row overflowed at ${width.toInt()}px / 2x');
      expect(find.text('在网页版打开'), findsWidgets);
    });
  }

  testWidgets('the settings hub keeps the plugin rows reachable at 320px / 2x', (tester) async {
    useNarrowSurface(tester);
    await _pump(tester, const SettingsScreen(), extra: _hub);
    expect(tester.takeException(), isNull, reason: 'a settings row overflowed at 320px / 2x');
    await tester.scrollUntilVisible(find.text('桌宠提醒'), 200);
    expect(find.text('桌宠提醒'), findsOneWidget);
  });

  testWidgets('a long plugin section title wraps instead of pushing the row out', (tester) async {
    useNarrowSurface(tester);
    final long = seatInventory(
      occupancy: {
        Slots.settingsSection: [occupant(Slots.settingsSection, probe, 'k4-probe', label: '一个很长很长的插件设置分节标题')],
      },
    );
    await tester.pumpWidget(
      goldenApp(
        home: const SettingsScreen(),
        brightness: Brightness.light,
        overrides: [
          ..._hub,
          ...seatOverrides(inventory: long),
        ],
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 600));
    expect(tester.takeException(), isNull, reason: 'a long section title overflowed its row');
  });
}
