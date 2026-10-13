import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:frames/shell/app_shell.dart';

import 'support/fake_phone.dart';
import 'support/render.dart';

/// Pictures to look at, one a page, written to build/pictures (not kept in the repository, and nothing is held to
/// them): the bar over the page of tools, the list of Settings, each tool's page of it, and the wide layout.
void main() {
  setUpAll(loadAppFonts);

  Future<void> picture(WidgetTester tester, String name, String? place, {Size size = phone, bool dark = true, void Function(FakePhone phone)? with_}) async {
    final fake = FakePhone();
    with_?.call(fake);
    await pumpApp(tester, size: size, dark: dark, home: AppShell(first: place));
    await settle(tester);
    await shot(tester, name);
    await endApp(tester);
  }

  testWidgets('the page of tools under the bar', (tester) => picture(tester, 'home', null));
  testWidgets('the list of settings', (tester) => picture(tester, 'settings', 'settings'));
  testWidgets('the list of settings on paper', (tester) => picture(tester, 'settings-light', 'settings', dark: false));
  testWidgets('the dock, floating', (tester) => picture(tester, 'settings-dock', 'settings/dock'));
  testWidgets('the dock, beside the camera', (tester) => picture(tester, 'settings-dock-camera', 'settings/dock', size: const Size(390, 1100), with_: (p) => p.dock['beside'] = true));
  testWidgets('amphetamine', (tester) => picture(tester, 'settings-amphetamine', 'settings/amphetamine', size: const Size(390, 1250), with_: (p) {
        p.awake['apps'] = ['com.example.maps'];
        p.awake['appNames'] = ['Maps'];
      }));
  testWidgets('the apps that keep it awake', (tester) => picture(tester, 'settings-amphetamine-apps', 'settings/amphetamine-apps'));
  testWidgets('frames', (tester) => picture(tester, 'settings-frames', 'settings/frames', size: const Size(390, 1100), with_: (p) => p.frames['hud'] = true));
  testWidgets('toasty', (tester) => picture(tester, 'settings-toasty', 'settings/toasty'));
  testWidgets('about', (tester) => picture(tester, 'settings-about', 'settings/about', size: const Size(390, 1900)));
  testWidgets('settings, opened out', (tester) => picture(tester, 'settings-wide', 'settings/dock', size: wideWindow));
  testWidgets('amphetamine\'s own page', (tester) => picture(tester, 'amphetamine', 'amphetamine', with_: (p) {
        p.awake['on'] = true;
        p.awake['forMs'] = 754000;
      }));
  testWidgets('toasty\'s own page', (tester) => picture(tester, 'toasty', 'toasty'));
  testWidgets('frames\' first page', (tester) => picture(tester, 'frames', 'frames'));
}
