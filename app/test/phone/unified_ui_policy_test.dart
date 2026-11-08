import 'package:flutter_test/flutter_test.dart';
import 'package:dsh_app/phone/phone_channel.dart';

void main() {
  test('unified UI is opt-in and survives unrelated policy changes', () {
    expect(PhonePolicies.fromMap({}).unifiedUi, isFalse);
    final initial = PhonePolicies.fromMap({
      'unifiedUi': true,
      'uiAllowedPackages': ['example.app'],
      'denyPackages': ['denied.app'],
    });
    final edited = initial.withConfirmMode(PhoneConfirmMode.ask)
        .withRule('other.app', AppRule.deny);
    expect(edited.unifiedUi, isTrue);
    expect(edited.toWire()['uiAllowedPackages'], ['example.app']);
    expect(edited.toWire()['denyPackages'], contains('denied.app'));
    final legacy = edited.withUnifiedUi(false);
    expect(legacy.unifiedUi, isFalse);
    expect(legacy.uiAllowedPackages, ['example.app']);
    expect(legacy.toWire().containsKey('unlocks'), isFalse);
  });
}
