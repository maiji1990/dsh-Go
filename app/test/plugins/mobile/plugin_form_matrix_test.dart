import 'package:dsh_app/state/plugin_config/plugin_config_memory.dart';
import 'package:dsh_app/ui/plugins/config/plugin_config_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../../engine_settings/support.dart' show settle;
import '../../plugin_config/support.dart';

void main() {
  for (final width in [320.0, 360.0, 393.0, 480.0, 600.0, 768.0, 1024.0]) {
    for (final scale in [1.0, 1.3, 2.0]) {
      for (final brightness in Brightness.values) {
        testWidgets('native long form ${width.toInt()}px $scale ${brightness.name}', (tester) async {
          tester.platformDispatcher.textScaleFactorTestValue = scale;
          addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
          await pumpConfigPage(
            tester,
            const PluginConfigPage(ns: 'translator'),
            MemoryPluginConfigApi([
              MemoryPluginEntry(
                ns: 'translator',
                schema: fields({for (var i = 0; i < 30; i++) 'field$i': str(zh: '设置项 $i', en: 'Field $i')}),
                base: {for (var i = 0; i < 30; i++) 'field$i': 'value-$i'},
              ),
            ]),
            brightness: brightness,
          );
          tester.view.physicalSize = Size(width * 2, 844 * 2);
          await settle(tester);
          expect(tester.takeException(), isNull);
          await tester.scrollUntilVisible(
            find.text('设置项 29'),
            300,
            scrollable: find.byWidgetPredicate((w) => w is Scrollable && w.axisDirection == AxisDirection.down).first,
          );
          await Scrollable.ensureVisible(tester.element(find.text('设置项 29')), alignment: 0.5);
          await settle(tester);
          await tester.tap(find.text('设置项 29'));
          await settle(tester);
          expect(find.byType(TextField), findsOneWidget);
          expect(tester.takeException(), isNull);
        });
      }
    }
  }
  for (final brightness in Brightness.values) {
    testWidgets('native compact form golden ${brightness.name}', (tester) async {
      tester.platformDispatcher.textScaleFactorTestValue = 2;
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      await pumpConfigPage(tester, const PluginConfigPage(ns: 'translator'), sampleConfigApi(), brightness: brightness);
      tester.view.physicalSize = const Size(640, 1688);
      await settle(tester);
      expect(tester.takeException(), isNull);
      await expectLater(
        find.byType(MaterialApp),
        matchesGoldenFile('goldens/plugin_form_320_2x_${brightness.name}.png'),
      );
    });
  }
  testWidgets('native text editor stays usable above the keyboard', (tester) async {
    await pumpConfigPage(tester, const PluginConfigPage(ns: 'translator'), sampleConfigApi());
    tester.view.physicalSize = const Size(640, 1280);
    await settle(tester);
    await tester.tap(find.text('目标语言'));
    await settle(tester);
    tester.view.viewInsets = const FakeViewPadding(bottom: 480);
    await settle(tester);
    final input = find.byType(TextField);
    expect(input, findsOneWidget);
    expect(tester.getRect(input).bottom, lessThanOrEqualTo(400));
    await tester.enterText(input, 'en');
    expect(tester.takeException(), isNull);
  });
}
