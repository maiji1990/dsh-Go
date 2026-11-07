import 'package:flutter/foundation.dart';

/// Presentation only. These modes never grant plugin or bridge permissions.
enum MobileAdaptMode { pluginResponsive, automatic, compatibility, desktop }

@immutable
class PluginMobilePolicy {
  const PluginMobilePolicy({
    this.mode = MobileAdaptMode.automatic,
    this.compact,
    this.rules = const {},
    this.overrideMode,
  });

  final MobileAdaptMode mode;
  final double? compact;
  final String? overrideMode;
  final Map<String, bool> rules;

  Map<String, Object> toJson() => {
    'mode': mode.name,
    'compact': ?compact,
    'overrideMode': ?overrideMode,
    if (rules.isNotEmpty) 'rules': rules,
  };
}

abstract final class MobilePolicyResolver {
  static PluginMobilePolicy resolve({required bool enabled, String? override, String? declared, bool probe = false}) {
    MobileAdaptMode? mode(String? name) => MobileAdaptMode.values.where((m) => m.name == name).firstOrNull;
    return PluginMobilePolicy(
      overrideMode: enabled && !probe ? mode(override)?.name : null,
      mode: !enabled || probe ? MobileAdaptMode.desktop : mode(override) ?? mode(declared) ?? MobileAdaptMode.automatic,
    );
  }
}
