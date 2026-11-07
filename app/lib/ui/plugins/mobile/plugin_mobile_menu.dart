import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../l10n/l10n.dart';
import '../../../state/settings_state.dart';
import 'plugin_mobile_policy.dart';

/// Available on both seat pages and plugins' own configuration islands.
class PluginMobileMenu extends ConsumerWidget {
  const PluginMobileMenu({super.key, required this.package});
  final String package;

  @override
  Widget build(BuildContext context, WidgetRef ref) => PopupMenuButton<String>(
    tooltip: context.l10n.pluginsMobileLayout,
    icon: const Icon(Icons.tune),
    initialValue: ref.watch(settingsProvider.select((s) => s.pluginMobileModes[package])) ?? 'default',
    onSelected: (mode) =>
        ref.read(settingsProvider.notifier).setPluginMobileMode(package, mode == 'default' ? null : mode),
    itemBuilder: (_) => [
      PopupMenuItem(value: 'default', child: Text(context.l10n.pluginsMobileDefault)),
      for (final mode in MobileAdaptMode.values)
        PopupMenuItem(
          value: mode.name,
          child: Text(switch (mode) {
            MobileAdaptMode.automatic => context.l10n.pluginsMobileAutomatic,
            MobileAdaptMode.compatibility => context.l10n.pluginsMobileCompatibility,
            MobileAdaptMode.pluginResponsive => context.l10n.pluginsMobileResponsive,
            MobileAdaptMode.desktop => context.l10n.pluginsMobileDesktop,
          }),
        ),
    ],
  );
}
