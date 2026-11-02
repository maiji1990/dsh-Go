// The label each plugin settings entry carries: a native form, the plugin's
// own web page, or read-only on this host.

import 'package:dsh_app/plugins/seams/seams.dart' show SeatInventory, Slots, SlotOccupant;
import 'package:dsh_app/state/plugin_config/plugin_config_state.dart';
import 'package:dsh_app/ui/plugins/config/plugin_config_entries.dart';
import 'package:dsh_app/ui/plugins/config/plugin_config_page.dart' show pluginConfigOwnPageRequest;
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter_test/flutter_test.dart';

PluginConfigForm form(String ns, {bool autoGenerate = true}) => PluginConfigForm(
  SettingsNamespaceView(
    ns: ns,
    schema: SchemaNode.fromJson({
      'type': 'object',
      'dict': {
        'enabled': {'type': 'boolean'},
      },
    }),
    value: const {},
    revision: 0,
    autoGenerate: autoGenerate,
  ),
);

/// The bundle that makes [ns] a plugin entry (what the Host lists).
PluginBundle bundleFor(String ns) => PluginBundle.fromJson({
  'name': '@acme/$ns',
  'enabled': true,
  'installed': true,
  'source': '@acme/$ns@1.0.0',
  'rows': [
    {'rowId': ns, 'moduleName': '@acme/$ns'},
  ],
});

PluginConfigScope scopeFor(String ns) => PluginConfigScope.of([bundleFor(ns)]);

PluginConfigState state({
  Map<String, PluginConfigForm>? forms,
  Map<String, PluginConfigEntry>? entries,
  PluginConfigScope scope = PluginConfigScope.none,
  bool writable = true,
}) => PluginConfigState(forms: forms ?? const {}, entries: entries, scope: scope, writable: writable);

void main() {
  test('a generated form reads as native', () {
    final s = state(forms: {'a': form('a')}, scope: scopeFor('a'));
    expect(pluginConfigMode(s, 'a'), PluginConfigMode.native);
    expect(pluginConfigModeNote(s, 'a'), '原生');
    expect(pluginConfigModeHint(s, 'a'), '原生表单，可直接在 App 里编辑');
  });

  test('autoGenerate: false reads as the plugin web page', () {
    final s = state(forms: {'a': form('a', autoGenerate: false)}, scope: scopeFor('a'));
    expect(pluginConfigMode(s, 'a'), PluginConfigMode.web);
    expect(pluginConfigModeNote(s, 'a'), '网页');
    expect(pluginConfigModeHint(s, 'a'), '插件自带网页界面，在 App 内嵌打开');
  });

  test('a host that refuses writes, or an entry the host locked, reads read-only', () {
    expect(
      pluginConfigMode(state(forms: {'a': form('a')}, scope: scopeFor('a'), writable: false), 'a'),
      PluginConfigMode.readOnly,
    );
    final locked = state(
      forms: {'a': form('a')},
      entries: {
        'a': const PluginConfigEntry('a', writable: false),
        // Scope names the entry, so it must be in the bundle too.
      },
      scope: scopeFor('a'),
    );
    expect(pluginConfigMode(locked, 'a'), PluginConfigMode.readOnly);
    expect(pluginConfigModeNote(locked, 'a'), '只读');
  });

  test('an unread entry without a form says read-only, not native', () {
    final s = state(entries: {'a': const PluginConfigEntry('a', configurable: false)});
    expect(pluginConfigMode(s, 'a'), PluginConfigMode.readOnly);
    expect(pluginConfigMode(s, 'unknown'), isNull);
  });

  test('a plugin page with a reported settings section becomes an island request', () {
    final bundle = bundleFor('a');
    final inventory = SeatInventory(
      webReachable: true,
      occupancy: {
        Slots.settingsSection: [
          const SlotOccupant(slot: Slots.settingsSection, package: '@acme/a', cell: 'a-settings'),
        ],
      },
    );
    final request = pluginConfigOwnPageRequest(form('a', autoGenerate: false), 'a', [bundle], inventory);
    expect(request?.slot, Slots.settingsSection);
    expect(request?.cell, 'a-settings');
    expect(request?.package, '@acme/a');

    expect(pluginConfigOwnPageRequest(form('a'), 'a', [bundle], inventory), isNull);
    expect(
      pluginConfigOwnPageRequest(form('a', autoGenerate: false), 'a', [
        bundle,
      ], SeatInventory(webReachable: false, occupancy: inventory.occupancy)),
      isNull,
    );
    expect(pluginConfigOwnPageRequest(form('a', autoGenerate: false), 'a', [bundle], null), isNull);
  });
}
