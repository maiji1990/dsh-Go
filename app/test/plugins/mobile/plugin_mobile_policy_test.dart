import 'package:dsh_app/ui/plugins/mobile/plugin_mobile_policy.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:dsh_app/state/settings_state.dart';

void main() {
  test('global disable and probes suppress semantic changes', () {
    expect(MobilePolicyResolver.resolve(enabled: false, override: 'automatic').mode, MobileAdaptMode.desktop);
    expect(MobilePolicyResolver.resolve(enabled: true, probe: true).mode, MobileAdaptMode.desktop);
  });
  test('user policy takes precedence over declaration and unknown modes are ignored', () {
    expect(
      MobilePolicyResolver.resolve(enabled: true, override: 'compatibility', declared: 'pluginResponsive').mode,
      MobileAdaptMode.compatibility,
    );
    expect(
      MobilePolicyResolver.resolve(enabled: true, declared: 'pluginResponsive').mode,
      MobileAdaptMode.pluginResponsive,
    );
    expect(MobilePolicyResolver.resolve(enabled: true, override: 'future-mode').mode, MobileAdaptMode.automatic);
  });
  test('global and per-package modes persist without replacing other packages', () async {
    SharedPreferences.setMockInitialValues({'plugin_mobile_modes': '{"old":"compatibility","future":"unknown"}'});
    final container = ProviderContainer();
    addTearDown(container.dispose);
    final settings = container.read(settingsProvider.notifier);
    await settings.setMobileAdapterEnabled(false);
    await settings.setPluginMobileMode('new', 'pluginResponsive');
    expect(container.read(settingsProvider).mobileAdapterEnabled, isFalse);
    expect(container.read(settingsProvider).pluginMobileModes, {'old': 'compatibility', 'new': 'pluginResponsive'});
    await settings.setPluginMobileMode('new', null);
    expect(container.read(settingsProvider).pluginMobileModes, {'old': 'compatibility'});
    final prefs = await SharedPreferences.getInstance();
    expect(prefs.getBool('mobile_adapter_enabled'), isFalse);
    expect(prefs.getString('plugin_mobile_modes'), '{"old":"compatibility"}');
  });
}
