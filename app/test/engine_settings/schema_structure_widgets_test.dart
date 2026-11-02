import 'dart:convert';
import 'dart:io';

import 'package:dsh_app/ui/engine_settings/engine_settings_page.dart';
import 'package:dsh_app/ui/engine_settings/field_sheets.dart';
import 'package:dsh_app/ui/engine_settings/schema_form.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support.dart';
import '../shell/golden_harness.dart' show goldenApp;

Object fixture(String name) =>
    jsonDecode(File('test/engine_settings/fixtures/$name.json').readAsStringSync()) as Object;

FakeSettingsHost schemaHost(Object schema, {Map<String, Object?> base = const {}}) => FakeSettingsHost(
  describe: {
    'namespaces': [
      {'ns': 'structure', 'schema': schema, 'value': base, 'base': base, 'revision': 0},
    ],
  },
);

void main() {
  testWidgets('local fallback keeps its supported sibling interactive', (tester) async {
    final host = schemaHost(
      {
        'type': 'intersect',
        'list': [
          {
            'type': 'object',
            'dict': {
              'enabled': {'type': 'boolean'},
            },
          },
          {'type': 'unknown-extension'},
        ],
      },
      base: {'enabled': false},
    );
    await pumpPage(
      tester,
      const EngineSettingsPage(ns: 'structure'),
      overrides: engineOverrides(DshClient.withCarrier(host)),
    );
    expect(find.byType(SchemaUnsupportedField), findsOneWidget);
    expect(find.byType(Switch), findsOneWidget);
    await tester.tap(find.byType(Switch));
    await flush(tester);
    expect(host.mutates().single['ops'], [
      {
        'op': 'set',
        'path': ['enabled'],
        'value': true,
      },
    ]);
  });

  testWidgets('disabled parent keeps nested toggle disabled', (tester) async {
    final host = schemaHost({
      'type': 'object',
      'dict': {
        'locked': {
          'type': 'object',
          'meta': {'disabled': true},
          'dict': {
            'enabled': {'type': 'boolean'},
          },
        },
      },
    });
    await pumpPage(
      tester,
      const EngineSettingsPage(ns: 'structure'),
      overrides: engineOverrides(DshClient.withCarrier(host)),
    );
    expect(tester.widget<Switch>(find.byType(Switch)).onChanged, isNull);
    expect(host.mutates(), isEmpty);
  });

  testWidgets('a union of objects gets a raw JSON editor', (tester) async {
    final host = schemaHost(
      {
        'type': 'object',
        'dict': {
          'retry': {
            'type': 'union',
            'list': [
              {
                'type': 'object',
                'dict': {
                  'attempts': {'type': 'number'},
                },
              },
              {
                'type': 'object',
                'dict': {
                  'delayMs': {'type': 'number'},
                },
              },
            ],
          },
        },
      },
      base: {
        'retry': {'attempts': 2},
      },
    );
    await pumpPage(
      tester,
      const EngineSettingsPage(ns: 'structure'),
      overrides: engineOverrides(DshClient.withCarrier(host)),
    );
    expect(find.byType(SchemaUnsupportedField), findsNothing);
    expect(find.text('高级：直接编辑这一项的 JSON'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('structure\u0000retry')));
    await flush(tester);
    expect(find.byType(FieldSheetFrame), findsOneWidget);
    final input = find.byKey(const ValueKey('schema-json-input'));
    expect(input, findsOneWidget);
    await tester.enterText(input, '{"attempts": 3}');
    await tester.tap(find.text('完成'));
    await flush(tester);
    expect(host.mutates().single['ops'], [
      {
        'op': 'set',
        'path': ['retry'],
        'value': {'attempts': 3},
      },
    ]);
  });

  testWidgets('a secret below a union of objects keeps the web fallback', (tester) async {
    final host = schemaHost({
      'type': 'object',
      'dict': {
        'account': {
          'type': 'union',
          'list': [
            {
              'type': 'object',
              'dict': {
                'key': {
                  'type': 'string',
                  'meta': {'role': 'secret'},
                },
              },
            },
            {
              'type': 'object',
              'dict': {
                'token': {
                  'type': 'string',
                  'meta': {'role': 'secret'},
                },
              },
            },
          ],
        },
      },
    });
    await pumpPage(
      tester,
      const EngineSettingsPage(ns: 'structure'),
      overrides: engineOverrides(DshClient.withCarrier(host)),
    );
    expect(find.text('高级：直接编辑这一项的 JSON'), findsNothing);
    expect(find.byType(SchemaUnsupportedField), findsWidgets);
  });

  testWidgets('an object-valued dict opens a keyed form', (tester) async {
    final host = schemaHost(
      {
        'type': 'object',
        'dict': {
          'limits': {
            'type': 'dict',
            'inner': {
              'type': 'object',
              'dict': {
                'contextWindow': {'type': 'number'},
              },
            },
          },
        },
      },
      base: {
        'limits': {
          'a': {'contextWindow': 1},
        },
      },
    );
    await pumpPage(
      tester,
      const EngineSettingsPage(ns: 'structure'),
      overrides: engineOverrides(DshClient.withCarrier(host)),
    );
    expect(find.byType(SchemaUnsupportedField), findsNothing);
    await tester.tap(find.byKey(const ValueKey('structure\u0000limits')));
    await flush(tester);
    expect(find.byType(FieldSheetFrame), findsOneWidget);
    await tester.tap(find.text('a').last);
    await flush(tester);
    final input = find.byType(TextField).first;
    await tester.enterText(input, '2');
    await tester.tap(find.text('完成').last);
    await flush(tester);
    expect(host.mutates().single['ops'], [
      {
        'op': 'set',
        'path': ['limits'],
        'value': {
          'a': {'contextWindow': 2},
        },
      },
    ]);
  });

  for (final brightness in Brightness.values) {
    for (final scale in [1.3, 2.0]) {
      testWidgets('real voice schema narrow ${brightness.name} $scale', (tester) async {
        final host = schemaHost(fixture('dsh-ears-0.4.3'));
        tester.view.devicePixelRatio = 1;
        tester.view.physicalSize = const Size(320, 900);
        addTearDown(tester.view.reset);
        await tester.pumpWidget(
          goldenApp(
            brightness: brightness,
            overrides: engineOverrides(DshClient.withCarrier(host)),
            home: Builder(
              builder: (context) => MediaQuery(
                data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(scale)),
                child: const EngineSettingsPage(ns: 'structure', title: '语音输入'),
              ),
            ),
          ),
        );
        await flush(tester);
        expect(find.byType(SchemaUnsupportedField), findsNothing);
        expect(find.byType(SchemaFieldTile), findsWidgets);
        expect(tester.takeException(), isNull);
      });
    }
  }
}
